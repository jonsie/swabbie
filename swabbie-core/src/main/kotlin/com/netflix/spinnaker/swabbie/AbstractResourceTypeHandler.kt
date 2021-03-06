/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie

import com.google.common.collect.Lists
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.lock.LockManager.LockOptions
import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.moniker.frigga.FriggaReflectiveNamer
import com.netflix.spinnaker.swabbie.events.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractResourceTypeHandler<T : Resource>(
  registry: Registry,
  val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceRepository: ResourceTrackingRepository,
  private val resourceStateRepository: ResourceStateRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver<T>,
  private val notifiers: List<Notifier>,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val lockingService: Optional<LockingService>,
  private val retrySupport: RetrySupport
) : ResourceTypeHandler<T>, MetricsSupport(registry) {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceOwnerField: String = "swabbieResourceOwner"

  private val timeoutMillis: Long = 5000
  private val maxAttempts: Int = 3

  /**
   * deletes a marked resource. Each handler must implement this function.
   * Deleted resources are produced via [DeleteResourceEvent]
   * for additional processing
   */
  abstract fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration)

  /**
   * deletes a resource in a reversible way.
   * Soft deleted resources are produced via [SoftDeleteResourceEvent]
   * for additional processing
   */
  abstract fun softDeleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration)

  /**
   * restores a soft-deleted resource.
   */
  abstract fun restoreResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    execute(workConfiguration, postMark, Action.MARK)
  }

  /**
   * deletes violating resources
   */
  override fun delete(workConfiguration: WorkConfiguration, postDelete: () -> Unit) {
    execute(workConfiguration, postDelete, Action.DELETE)
  }

  /**
   * Notifies resource owners
   */
  override fun notify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    execute(workConfiguration, postNotify, Action.NOTIFY)
  }

  /**
   * Soft deletes expiring resources
   */
  override fun softDelete(workConfiguration: WorkConfiguration, postSoftDelete: () -> Unit) {
    execute(workConfiguration, postSoftDelete, Action.SOFTDELETE)
  }

  /**
   * Dispatches work based on passed action
   */
  private fun execute(workConfiguration: WorkConfiguration, callback: () -> Unit, action: Action) {
    when (action) {
      Action.MARK -> withLocking(action, workConfiguration) {
        doMark(workConfiguration, callback)
      }

      Action.NOTIFY -> withLocking(action, workConfiguration) {
        doNotify(workConfiguration, callback)
      }

      Action.SOFTDELETE -> withLocking(action, workConfiguration) {
        doSoftDelete(workConfiguration, callback)
      }

      Action.DELETE -> withLocking(action, workConfiguration) {
        doDelete(workConfiguration, callback)
      }

      else -> log.warn("Invalid action {}", action.name)
    }
  }

  private fun withLocking(
    action: Action,
    workConfiguration: WorkConfiguration,
    callback: () -> Unit
  ) {
    if (lockingService.isPresent) {
      val normalizedLockName = ("${action.name}." + workConfiguration.namespace)
        .replace(":", ".")
        .toLowerCase()
      val lockOptions = LockOptions()
        .withLockName(normalizedLockName)
        .withMaximumLockDuration(lockingService.get().swabbieMaxLockDuration)
      lockingService.get().acquireLock(lockOptions) {
        callback.invoke()
      }
    } else {
      log.warn("***Locking not ENABLED, continuing without locking for ${javaClass.simpleName}***")
      callback.invoke()
    }
  }

  override fun markResource(resourceId: String, onDemandMarkData: OnDemandMarkData, workConfiguration: WorkConfiguration) {
    val resource = getCandidate(resourceId, "", workConfiguration)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    var markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)

    if (markedResource != null) {
      markedResource = markedResource.copy(
        resource = resource,
        summaries = listOf(Summary("Resource marked via the api", AlwaysCleanRule::class.java.simpleName)),
        namespace = workConfiguration.namespace,
        resourceOwner = onDemandMarkData.resourceOwner,
        projectedSoftDeletionStamp = onDemandMarkData.projectedSoftDeletionStamp,
        projectedDeletionStamp = onDemandMarkData.projectedDeletionStamp,
        notificationInfo = onDemandMarkData.notificationInfo
      )
    } else {
      markedResource = MarkedResource(
        resource = resource,
        summaries = listOf(Summary("Resource marked via the api", AlwaysCleanRule::class.java.simpleName)),
        namespace = workConfiguration.namespace,
        resourceOwner = onDemandMarkData.resourceOwner,
        projectedSoftDeletionStamp = onDemandMarkData.projectedSoftDeletionStamp,
        projectedDeletionStamp = onDemandMarkData.projectedDeletionStamp,
        notificationInfo = onDemandMarkData.notificationInfo
      )
    }

    log.debug("Marking resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    resourceRepository.upsert(markedResource)
    applicationEventPublisher.publishEvent(MarkResourceEvent(markedResource, workConfiguration))
  }

  override fun softDeleteResource(resourceId: String, workConfiguration: WorkConfiguration) {
    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    log.debug("Soft deleting resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    softDeleteResources(listOf(markedResource), workConfiguration)
  }

  override fun deleteResource(resourceId: String, workConfiguration: WorkConfiguration) {
    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    log.debug("Deleting resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    deleteResources(listOf(markedResource), workConfiguration)
  }

  override fun restoreResource(resourceId: String, workConfiguration: WorkConfiguration) {
    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    log.debug("Restoring resource $resourceId in namespace ${workConfiguration.namespace}.")
    restoreResources(listOf(markedResource), workConfiguration)
  }

  private fun doMark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    // initialize counters
    exclusionCounters[Action.MARK] = AtomicInteger(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    val markedResources = mutableListOf<MarkedResource>()
    try {
      log.info("${javaClass.simpleName} running. Configuration: ", workConfiguration)
      val candidates: List<T>? = getCandidates(workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        return
      }
      log.info("fetched {} resources. Configuration: {}", candidates.size, workConfiguration)
      totalResourcesVisitedCounter.set(candidates.size)

      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

      val filteredCandidatesWithOwners = candidates
        .withResolvedOwners(workConfiguration)
        .filter { !shouldExcludeResource(it, workConfiguration, optedOutResourceStates, Action.MARK) }

      preProcessCandidates(filteredCandidatesWithOwners, workConfiguration)
        .let { filteredCandidates ->
          val maxItemsToProcess = Math.min(filteredCandidates.size, workConfiguration.maxItemsProcessedPerCycle)

          // list of currently marked & stored resources
          val markedCandidates: List<MarkedResource> = resourceRepository.getMarkedResources()
            .filter {
              it.namespace == workConfiguration.namespace
            }

          for (candidate in filteredCandidates) {
            if (candidateCounter.get() > maxItemsToProcess) {
              log.info("Max items to process reached {}...", maxItemsToProcess)
              break
            }

            try {
              val violations: List<Summary> = candidate.getViolations()
              markedCandidates.find {
                it.resourceId == candidate.resourceId
              }.let { alreadyMarkedCandidate ->
                when {
                  violations.isEmpty() -> ensureResourceUnmarked(alreadyMarkedCandidate, workConfiguration)
                  alreadyMarkedCandidate == null -> {
                    val newMarkedResource = MarkedResource(
                      resource = candidate,
                      summaries = violations,
                      namespace = workConfiguration.namespace,
                      resourceOwner = candidate.details[resourceOwnerField] as String,
                      projectedSoftDeletionStamp = softDeletionTimestamp(workConfiguration),
                      projectedDeletionStamp = deletionTimestamp(workConfiguration)
                    )

                    if (!workConfiguration.dryRun) {
                      //todo eb: should this upsert event happen in ResourceTrackingManager?
                      resourceRepository.upsert(newMarkedResource)
                      applicationEventPublisher.publishEvent(MarkResourceEvent(newMarkedResource, workConfiguration))
                      log.info("Marked resource {} for deletion", newMarkedResource)
                    }

                    candidateCounter.incrementAndGet()
                    violationCounter.addAndGet(violations.size)
                    markedResources.add(newMarkedResource)
                  }
                  else -> {
                    // already marked, skipping.
                    log.debug("Already marked resource " + alreadyMarkedCandidate.resource.resourceId + " ...skipping")
                  }
                }
              }
            } catch (e: Exception) {
              log.error("Failed while invoking ${javaClass.simpleName}", e)
              recordFailureForAction(Action.MARK, workConfiguration, e)
            }
          }
        }

      printResult(candidateCounter, totalResourcesVisitedCounter, workConfiguration, markedResources, Action.MARK)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  override fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvaluation {
    val candidate = getCandidate(resourceId, resourceName, workConfiguration) ?: return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      false,
      "Resource does not exist in the given namespace",
      emptyList()
    )

    val resourceState = resourceStateRepository.get(resourceId, workConfiguration.namespace)
    if (resourceState != null && resourceState.optedOut) {
      return ResourceEvaluation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been opted out",
        emptyList()
      )
    }

    val candidates = listOf(candidate)
      .withResolvedOwners(workConfiguration)
      .filter { !shouldExcludeResource(it, workConfiguration, emptyList(), Action.MARK) }

    if (candidates.isEmpty()) {
      return ResourceEvaluation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been excluded from marking",
        emptyList()
      )
    }

    val preprocessedCandidate = preProcessCandidates(candidates, workConfiguration).first()
    val wouldMark = preprocessedCandidate.getViolations().isNotEmpty()
    return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      wouldMark,
      if (wouldMark) "Resource has violations" else "Resource does not have violations",
      preprocessedCandidate.getViolations()
    )
  }

  private fun ensureResourceUnmarked(
    markedResource: MarkedResource?,
    workConfiguration: WorkConfiguration
  ) {
    if (markedResource != null && !workConfiguration.dryRun) {
      try {
        log.info("{} is no longer a candidate.", markedResource)
        resourceRepository.remove(markedResource)
        applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
      } catch (e: Exception) {
        log.error("Failed to unmark resource {}", markedResource, e)
      }
    }
  }

  private fun softDeletionTimestamp(workConfiguration: WorkConfiguration): Long =
    (workConfiguration.retention + 1).days.fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()

  private fun deletionTimestamp(workConfiguration: WorkConfiguration): Long {
    if (!workConfiguration.softDelete.enabled) {
      // soft deleting is not enabled, so our deletion time is the same as what our soft deletion time would be
      return softDeletionTimestamp(workConfiguration)
    }
    return (workConfiguration.retention + workConfiguration.softDelete.waitingPeriodDays + 1)
      .days.fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()
  }


  private fun printResult(
    candidateCounter: AtomicInteger,
    totalResourcesVisitedCounter: AtomicInteger,
    workConfiguration: WorkConfiguration,
    markedResources: List<MarkedResource>,
    action: Action
  ) {
    val totalProcessed = Math.min(workConfiguration.maxItemsProcessedPerCycle, totalResourcesVisitedCounter.get())
    log.info("** ${action.name} Summary: {} CANDIDATES OUT OF {} PROCESSED. {} SCANNED. EXCLUDED {}. CONFIGURATION: {}**",
      candidateCounter.get(),
      totalProcessed,
      totalResourcesVisitedCounter.get(),
      exclusionCounters[action],
      workConfiguration
    )

    if (candidateCounter.get() > 0) {
      log.debug("** ${action.name} Result. Configuration: {} **", workConfiguration)
      markedResources.forEach {
        log.debug(it.toString())
      }
    }
  }

  private fun shouldExcludeResource(
    resource: T,
    workConfiguration: WorkConfiguration,
    optedOutResourceStates: List<ResourceState>,
    action: Action
  ): Boolean {
    if (resource.getViolations().any { summary -> summary.ruleName == AlwaysCleanRule::class.java.simpleName }) {
      return false
    }

    val creationDate = Instant.ofEpochMilli(resource.createTs).atZone(ZoneId.systemDefault()).toLocalDate()
    if (action != Action.NOTIFY && DAYS.between(creationDate, LocalDate.now()) < workConfiguration.maxAge) {
      log.debug("Excluding resource (newer than {} days) {}", workConfiguration.maxAge, resource)
      return true
    }

    optedOutResourceStates.find { it.markedResource.resourceId == resource.resourceId }?.let {
      // TODO: opting out should expire based on configured time
      log.debug("Skipping Opted out resource {}", resource)
      return true
    }

    return shouldExclude(resource, workConfiguration, exclusionPolicies, log).also { excluded ->
      if (excluded) {
        exclusionCounters[action]?.incrementAndGet()
      }
    }
  }

  private fun doSoftDelete(workConfiguration: WorkConfiguration, postSoftDelete: () -> Unit) {
    if (!workConfiguration.softDelete.enabled) {
      log.debug("Soft delete not enabled for $workConfiguration")
      return
    }
    exclusionCounters[Action.SOFTDELETE] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    val softDeletedResources = mutableListOf<MarkedResource>()
    try {
      log.info("${javaClass.simpleName} running. Configuration: ", workConfiguration)
      val resourcesToSoftDelete: List<MarkedResource> = resourceRepository.getMarkedResourcesToSoftDelete()
        .filter { it.notificationInfo != null && !workConfiguration.dryRun }
        .sortedBy { it.resource.createTs }

      if (resourcesToSoftDelete.isEmpty()) {
        log.debug("Nothing to soft delete. Configuration: {}", workConfiguration)
        return
      }
      log.debug("Found ${resourcesToSoftDelete.size} resource(s) to soft delete")

      //todo eb: seems when someone opts out we should remove them from the list of deletable,
      // instead of filtering them out every time every where.
      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

      // figure out if they still qualify to be any sort of deleted, and deal with it if they don't
      val candidates: List<T>? = getCandidates(workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        resourcesToSoftDelete
          .forEach { ensureResourceUnmarked(it, workConfiguration) }
        log.info("Nothing to delete. No upstream resources, Configuration: {}", workConfiguration)
        return
      }

      candidates.withResolvedOwners(workConfiguration).also {
        preProcessCandidates(it, workConfiguration) //this re-evaluates everything and takes forever.
      }

      val confirmedSoftDeleteResources = resourcesToSoftDelete
        .filter { resource ->
          var shouldSkip = false
          val candidate = candidates.find { it.resourceId == resource.resourceId }
          if ((candidate == null || candidate.getViolations().isEmpty()) ||
            shouldExcludeResource(candidate, workConfiguration, optedOutResourceStates, Action.SOFTDELETE)) {
            shouldSkip = true
            ensureResourceUnmarked(resource, workConfiguration)
          }
          !shouldSkip
        }

      val maxItemsToProcess = Math.min(confirmedSoftDeleteResources.size, workConfiguration.maxItemsProcessedPerCycle)

      confirmedSoftDeleteResources.subList(0, maxItemsToProcess).let {
        partitionList(it, workConfiguration).forEach { partition ->
          if (!partition.isEmpty()) {
            try {
              softDeleteResources(partition, workConfiguration)
              candidateCounter.addAndGet(partition.size)
            } catch (e: Exception) {
              log.error("Failed to soft delete $it. Configuration: {}", workConfiguration, e)
              recordFailureForAction(Action.SOFTDELETE, workConfiguration, e)
            }
          }
        }
      }

      printResult(
        candidateCounter,
        AtomicInteger(confirmedSoftDeleteResources.size),
        workConfiguration,
        softDeletedResources,
        Action.SOFTDELETE
      )
    } finally {
      postSoftDelete.invoke()
    }
  }

  private fun doDelete(workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    exclusionCounters[Action.DELETE] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    val markedResources = mutableListOf<MarkedResource>()
    try {
      resourceRepository.getMarkedResourcesToDelete()
        .filter { it.notificationInfo != null && !workConfiguration.dryRun }
        .sortedBy { it.resource.createTs }
        .let { toDelete ->
          if (toDelete.isEmpty()) {
            log.info("Nothing to delete. Configuration: {}", workConfiguration)
            return
          }

          val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
            .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

          var candidates: List<T>? = getCandidates(workConfiguration)
          if (candidates == null) {
            toDelete
              .forEach { ensureResourceUnmarked(it, workConfiguration) }
            log.info("Nothing to delete. No upstream resources, Configuration: {}", workConfiguration)
            return
          }

          candidates = candidates
            .filter { candidate ->
              toDelete.any { candidate.resourceId == it.resourceId }
            }.withResolvedOwners(workConfiguration).also {
              preProcessCandidates(it, workConfiguration)
            }

          toDelete.filter { r ->
            var shouldSkip = false
            val candidate = candidates.find { it.resourceId == r.resourceId }
            if ((candidate == null || candidate.getViolations().isEmpty()) ||
              shouldExcludeResource(candidate, workConfiguration, optedOutResourceStates, Action.DELETE)) {
              shouldSkip = true
              ensureResourceUnmarked(r, workConfiguration)
            }
            !shouldSkip
          }.let { filteredCandidateList ->
            val maxItemsToProcess = Math.min(filteredCandidateList.size, workConfiguration.maxItemsProcessedPerCycle)
            filteredCandidateList.subList(0, maxItemsToProcess).let {
              partitionList(it, workConfiguration).forEach { partition ->
                if (!partition.isEmpty()) {
                  try {
                    deleteResources(partition, workConfiguration)
                    candidateCounter.addAndGet(partition.size)
                  } catch (e: Exception) {
                    log.error("Failed to delete $it. Configuration: {}", workConfiguration, e)
                    recordFailureForAction(Action.DELETE, workConfiguration, e)
                  }
                }
              }
            }
          }

          printResult(candidateCounter, AtomicInteger(toDelete.size), workConfiguration, markedResources, Action.DELETE)
        }
    } finally {
      postClean.invoke()
    }
  }

  /**
   * Partitions the list of marked resources to process:
   * For convenience, partitions are grouped by relevance (here the derived app they are associated with)
   */
  fun partitionList(
    markedResources: List<MarkedResource>,
    configuration: WorkConfiguration
  ): List<List<MarkedResource>> {
    val partitions = mutableListOf<List<MarkedResource>>()
    markedResources.groupBy {
      FriggaReflectiveNamer().deriveMoniker(it.resource).app
    }.map {
      Lists.partition(it.value, configuration.itemsProcessedBatchSize).forEach {
        partitions.add(it)
      }
    }

    return partitions.sortedByDescending { it.size }
  }

  private fun T.getViolations(): List<Summary> {
    return rules.mapNotNull {
      it.apply(this).summary
    }
  }

  private fun List<T>.withResolvedOwners(workConfiguration: WorkConfiguration): List<T> =
    this.map { candidate ->
      candidate.apply {
        set(
          resourceOwnerField,
          ownerResolver.resolve(candidate) ?: workConfiguration.notificationConfiguration.defaultDestination
        )
      }
    }

  private fun doNotify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    exclusionCounters[Action.NOTIFY] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    // used to print out result of resources for which notifications were sent
    val notifiedMarkedResource: MutableList<MarkedResource> = mutableListOf()
    try {
      if (workConfiguration.dryRun || !workConfiguration.notificationConfiguration.enabled) {
        log.info("Notification not enabled for {}. Skipping...", workConfiguration)
        return
      }

      val markedResources = resourceRepository.getMarkedResources()
        .filter {
          it.notificationInfo == null && workConfiguration.namespace == it.namespace
        }

      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter {
          it.markedResource.namespace == workConfiguration.namespace && it.optedOut
        }

      if (markedResources.isEmpty()) {
        log.info("Nothing to notify. Skipping...", workConfiguration)
        return
      }

      val maxItemsToProcess = Math.min(markedResources.size, workConfiguration.maxItemsProcessedPerCycle)
      markedResources.subList(0, maxItemsToProcess)
        .filter {
          !shouldExcludeResource(it.resource as T, workConfiguration, optedOutResourceStates, Action.NOTIFY)
        }.groupBy {
          it.resourceOwner
        }.forEach { ownersAndResources ->
          Lists.partition(
            ownersAndResources.value,
            workConfiguration.notificationConfiguration.itemsPerMessage
          ).forEach { partition ->
            try {
              sendNotification(ownersAndResources.key, workConfiguration, partition)
              partition.forEach { resource ->
                val offsetStampSinceMarked = ChronoUnit.MILLIS
                  .between(Instant.ofEpochMilli(resource.markTs!!), Instant.now(clock))

                resource
                  .apply {
                    projectedDeletionStamp += offsetStampSinceMarked
                    notificationInfo = NotificationInfo(
                      recipient = ownersAndResources.key,
                      notificationStamp = Instant.now(clock).toEpochMilli(),
                      notificationType = Notifier.NotificationType.EMAIL.name
                    )
                  }.also {
                    resourceRepository.upsert(it)
                    applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
                    candidateCounter.incrementAndGet()
                  }

                notifiedMarkedResource.add(resource)
              }
              log.debug("Notification sent to {} for {} resources", ownersAndResources.key, partition.size)
            } catch (e: Exception) {
              recordFailureForAction(Action.NOTIFY, workConfiguration, e)
            }
          }
        }

      printResult(
        candidateCounter,
        AtomicInteger(markedResources.size),
        workConfiguration,
        notifiedMarkedResource,
        Action.NOTIFY
      )
    } finally {
      postNotify.invoke()
    }
  }

  private fun sendNotification(
    owner: String,
    workConfiguration:
    WorkConfiguration,
    resources: List<MarkedResource>
  ) {
    notifiers.forEach { notifier ->
      workConfiguration.notificationConfiguration.types.forEach { notificationType ->
        if (notificationType.equals(Notifier.NotificationType.EMAIL.name, true)) {
          val notificationContext = mapOf(
            "resourceOwner" to owner,
            "resources" to resources,
            "configuration" to workConfiguration,
            "resourceType" to workConfiguration.resourceType.formatted(),
            "spinnakerLink" to workConfiguration.notificationConfiguration.resourceUrl,
            "optOutLink" to workConfiguration.notificationConfiguration.optOutBaseUrl
          )

          retrySupport.retry({
            notifier.notify(
              recipient = owner,
              messageType = notificationType,
              additionalContext = notificationContext
            )
          }, maxAttempts, timeoutMillis, true)
        }
      }
    }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this
}


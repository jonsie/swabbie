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

import com.netflix.spinnaker.swabbie.configuration.ScopeOfWorkConfiguration
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.MarkedResource

interface ResourceHandler {
  fun handles(resourceType: String, cloudProvider: String): Boolean
  fun getUpstreamResources(scopeOfWorkConfiguration: ScopeOfWorkConfiguration): List<Resource>?
  fun getUpstreamResource(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration): Resource?

  fun mark(scopeOfWorkConfiguration: ScopeOfWorkConfiguration)
  fun clean(markedResource: MarkedResource, scopeOfWorkConfiguration: ScopeOfWorkConfiguration)
}
/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import org.gradle.internal.cc.impl.CheckedFingerprint
import org.gradle.internal.configurationcache.ConfigurationCacheCheckFingerprintBuildOperationType
import org.gradle.internal.configurationcache.ConfigurationCacheCheckFingerprintBuildOperationType.FingerprintInvalidationReason
import org.gradle.internal.configurationcache.ConfigurationCacheCheckFingerprintBuildOperationType.ProjectInvalidationReasons
import org.gradle.internal.configurationcache.ConfigurationCacheLoadBuildOperationType
import org.gradle.internal.configurationcache.ConfigurationCacheStoreBuildOperationType
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationRunner
import org.gradle.internal.operations.CallableBuildOperation
import org.gradle.internal.operations.RunnableBuildOperation
import java.io.File


internal
fun <T : Any> BuildOperationRunner.withLoadOperation(block: () -> Pair<LoadResult, T>) =
    call(object : CallableBuildOperation<T> {
        override fun description(): BuildOperationDescriptor.Builder = BuildOperationDescriptor
            .displayName("Load configuration cache state")
            .progressDisplayName("Loading configuration cache state")
            .details(LoadDetails)

        override fun call(context: BuildOperationContext): T =
            block().let { (opResult, returnValue) ->
                context.setResult(opResult)
                returnValue
            }
    })


internal
fun BuildOperationRunner.withStoreOperation(@Suppress("UNUSED_PARAMETER") cacheKey: String, block: () -> StoreResult) =
    run(object : RunnableBuildOperation {
        override fun description(): BuildOperationDescriptor.Builder = BuildOperationDescriptor
            .displayName("Store configuration cache state")
            .progressDisplayName("Storing configuration cache state")
            .details(StoreDetails)

        override fun run(context: BuildOperationContext) {
            block().let {
                context.setResult(it)
                it.storeFailure?.let { failure -> context.failed(failure) }
            }
        }
    })


private
object LoadDetails : ConfigurationCacheLoadBuildOperationType.Details


internal
data class LoadResult(val stateFile: File, val originInvocationId: String? = null) : ConfigurationCacheLoadBuildOperationType.Result {
    override fun getCacheEntrySize(): Long = stateFile.length()
    override fun getOriginBuildInvocationId(): String? = originInvocationId
}


private
object StoreDetails : ConfigurationCacheStoreBuildOperationType.Details


internal
data class StoreResult(val stateFile: File, val storeFailure: Throwable?) : ConfigurationCacheStoreBuildOperationType.Result {
    override fun getCacheEntrySize(): Long = stateFile.length()
}


internal
fun BuildOperationRunner.withFingerprintCheckOperations(block: () -> CheckedFingerprint): CheckedFingerprint {
    return call(object : CallableBuildOperation<CheckedFingerprint> {
        override fun description() = BuildOperationDescriptor
            .displayName("Check configuration cache fingerprint")
            .details(FingerprintCheckDetails)

        override fun call(context: BuildOperationContext): CheckedFingerprint {
            return block().also {
                context.setResult(FingerprintCheckResult(it))
            }
        }
    })
}


private
object FingerprintCheckDetails : ConfigurationCacheCheckFingerprintBuildOperationType.Details


private
class FingerprintCheckResult(
    private val checkResult: CheckedFingerprint
) : ConfigurationCacheCheckFingerprintBuildOperationType.Result {
    init {
        if (checkResult is CheckedFingerprint.NotFound) {
            throw IllegalArgumentException("Should not emit build operation when entry is not found")
        }
    }

    override fun getBuildInvalidationReasons(): List<FingerprintInvalidationReason> {
        return when (checkResult) {
            is CheckedFingerprint.EntryInvalid -> listOf(FingerprintInvalidationReasonImpl(checkResult.reason.toString()))
            else -> emptyList()
        }
    }

    override fun getProjectInvalidationReasons(): List<ProjectInvalidationReasons> {
        return when (checkResult) {
            is CheckedFingerprint.ProjectsInvalid -> checkResult.invalidProjects.map {
                ProjectInvalidationReasonsImpl(
                    it.key.path, listOf(FingerprintInvalidationReasonImpl(it.value.toString()))
                )
            }
            else -> emptyList()
        }
    }

    private
    data class FingerprintInvalidationReasonImpl(private val message: String) : FingerprintInvalidationReason {
        override fun getMessage() = message
    }

    private
    data class ProjectInvalidationReasonsImpl(
        private val identityPath: String,
        private val invalidationReasons: List<FingerprintInvalidationReason>
    ) : ProjectInvalidationReasons {
        override fun getIdentityPath() = identityPath

        override fun getInvalidationReasons() = invalidationReasons
    }
}

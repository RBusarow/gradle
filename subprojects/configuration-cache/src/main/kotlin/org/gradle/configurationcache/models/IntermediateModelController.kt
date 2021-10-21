/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.configurationcache.models

import org.gradle.cache.internal.streams.BlockAddress
import org.gradle.cache.internal.streams.ValueStore
import org.gradle.configurationcache.CheckedFingerprint
import org.gradle.configurationcache.ConfigurationCacheIO
import org.gradle.configurationcache.ConfigurationCacheStateStore
import org.gradle.configurationcache.DefaultConfigurationCache
import org.gradle.configurationcache.StateType
import org.gradle.configurationcache.cacheentry.EntryDetails
import org.gradle.configurationcache.cacheentry.ModelKey
import org.gradle.configurationcache.extensions.uncheckedCast
import org.gradle.configurationcache.fingerprint.ConfigurationCacheFingerprintController
import org.gradle.configurationcache.serialization.IsolateOwner
import org.gradle.configurationcache.serialization.readNonNull
import org.gradle.configurationcache.serialization.runReadOperation
import org.gradle.configurationcache.serialization.runWriteOperation
import org.gradle.internal.concurrent.CompositeStoppable
import org.gradle.util.Path
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


/**
 * Responsible for loading and storing intermediate models used during tooling API build action execution.
 */
internal
class IntermediateModelController(
    private val host: DefaultConfigurationCache.Host,
    private val cacheIO: ConfigurationCacheIO,
    private val store: ConfigurationCacheStateStore,
    private val cacheFingerprintController: ConfigurationCacheFingerprintController
) : Closeable {
    private
    val projectValueStore by lazy {
        val writerFactory = { outputStream: OutputStream ->
            ValueStore.Writer<Any> { value ->
                val (context, codecs) = cacheIO.writerContextFor(outputStream, "values")
                context.push(IsolateOwner.OwnerHost(host), codecs.userTypesCodec)
                context.runWriteOperation {
                    write(value)
                }
                context.flush()
            }
        }
        val readerFactory = { inputStream: InputStream ->
            ValueStore.Reader<Any> {
                val (context, codecs) = cacheIO.readerContextFor(inputStream)
                context.push(IsolateOwner.OwnerHost(host), codecs.userTypesCodec)
                context.runReadOperation {
                    readNonNull()
                }
            }
        }
        store.createValueStore(StateType.ProjectModels, writerFactory, readerFactory)
    }

    private
    val previousIntermediateModels = ConcurrentHashMap<ModelKey, BlockAddress>()

    private
    val intermediateModels = ConcurrentHashMap<ModelKey, BlockAddress>()

    /**
     * All models used during execution.
     */
    val models: Map<ModelKey, BlockAddress>
        get() = Collections.unmodifiableMap(intermediateModels)

    fun restoreFromCacheEntry(entryDetails: EntryDetails, checkedFingerprint: CheckedFingerprint.ProjectsInvalid) {
        for (entry in entryDetails.intermediateModels) {
            if (entry.key.identityPath == null || !checkedFingerprint.invalidProjects.contains(entry.key.identityPath)) {
                // Can reuse the model
                previousIntermediateModels[entry.key] = entry.value
            }
        }
    }

    fun <T : Any> loadOrCreateIntermediateModel(identityPath: Path?, modelName: String, creator: () -> T): T {
        val key = ModelKey(identityPath, modelName)
        val addressOfCached = locateCachedModel(key)
        if (addressOfCached != null) {
            return projectValueStore.read(addressOfCached).uncheckedCast()
        }
        val model = if (identityPath != null) {
            cacheFingerprintController.collectFingerprintForProject(identityPath, creator)
        } else {
            creator()
        }
        val address = projectValueStore.write(model)
        intermediateModels[key] = address
        return model
    }

    private
    fun locateCachedModel(key: ModelKey): BlockAddress? {
        val cachedInCurrent = intermediateModels[key]
        if (cachedInCurrent != null) {
            return cachedInCurrent
        }
        val cachedInPrevious = previousIntermediateModels[key]
        if (cachedInPrevious != null) {
            intermediateModels[key] = cachedInPrevious
        }
        return cachedInPrevious
    }

    override fun close() {
        CompositeStoppable.stoppable(projectValueStore).stop()
    }
}

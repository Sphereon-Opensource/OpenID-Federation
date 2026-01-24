package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.log.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default implementation of CacheManager using in-memory Kache-backed caches.
 *
 * This implementation:
 * - Creates InMemoryScopedCache instances for each namespace
 * - Provides singleton cache instances per namespace
 * - Supports aggregate statistics across all caches
 *
 * Note: Cache creation in getOrCreate uses a simple check-and-set pattern.
 * In rare race conditions, a cache may be created twice but this is safe
 * as both would be valid instances.
 */
class DefaultCacheManager : CacheManager {

    private val logger = Log.app().withTag("sphereon:oidf:cache:manager")
    private val caches = mutableMapOf<String, ScopedCache<*, *>>()
    private val cacheLock = Mutex()

    @Suppress("UNCHECKED_CAST")
    override fun <K : Any, V : Any> getOrCreate(requirements: CacheRequirements): ScopedCache<K, V> {
        // Check if cache already exists (fast path)
        caches[requirements.namespace]?.let {
            return it as ScopedCache<K, V>
        }

        // Create cache if not exists
        // Note: In rare race conditions, we might create a cache twice,
        // but this is safe as both would be valid instances
        logger.debug(
            "Creating cache for namespace: ${requirements.namespace}",
            metadata = mapOf(
                "maxSize" to requirements.maxLocalEntries.toString(),
                "locality" to requirements.locality.name,
                "tags" to requirements.tags.joinToString(",")
            )
        )

        val cache = InMemoryScopedCache<K, V>(
            namespace = requirements.namespace,
            maxSize = requirements.maxLocalEntries,
            ttlConfig = requirements.ttlConfig
        )

        // Store and return (last write wins in race condition)
        caches[requirements.namespace] = cache
        return cache
    }

    @Suppress("UNCHECKED_CAST")
    override fun <K : Any, V : Any> get(namespace: String): ScopedCache<K, V>? {
        return caches[namespace] as? ScopedCache<K, V>
    }

    override fun exists(namespace: String): Boolean {
        return caches.containsKey(namespace)
    }

    override suspend fun getStatistics(namespace: String): CacheStatistics? {
        return caches[namespace]?.getStatistics()
    }

    override suspend fun aggregateStats(): Map<String, CacheStatistics> {
        return cacheLock.withLock {
            caches.mapValues { (_, cache) ->
                try {
                    cache.getStatistics()
                } catch (e: Exception) {
                    logger.warn("Failed to get statistics for cache: ${cache.namespace} - ${e.message}")
                    CacheStatistics.empty(cache.namespace)
                }
            }
        }
    }

    override fun getNamespaces(): Set<String> {
        return caches.keys.toSet()
    }

    override suspend fun clear(namespace: String) {
        caches[namespace]?.clear()
        logger.debug("Cleared cache: $namespace")
    }

    override suspend fun clearAll() {
        cacheLock.withLock {
            caches.values.forEach { it.clear() }
        }
        logger.debug("Cleared all caches")
    }

    override suspend fun evictAllExpired() {
        cacheLock.withLock {
            caches.values.forEach { it.evictExpired() }
        }
        logger.debug("Evicted expired entries from all caches")
    }

    override suspend fun shutdown() {
        cacheLock.withLock {
            caches.values.forEach { cache ->
                try {
                    cache.close()
                } catch (e: Exception) {
                    logger.warn("Failed to close cache: ${cache.namespace} - ${e.message}")
                }
            }
            caches.clear()
        }
        logger.info("Cache manager shutdown complete")
    }
}

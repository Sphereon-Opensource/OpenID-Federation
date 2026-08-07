package com.sphereon.openid.fed.core.cache

import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheSerializer
import com.sphereon.core.api.cache.CacheSerializers
import com.sphereon.core.api.cache.DefaultCacheManager
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.core.api.log.Log

/**
 * Thin OIDF helpers around IDK [CacheManager] / [ScopedCache].
 *
 * ## Boundary
 * - Prefer IDK types everywhere (`com.sphereon.core.api.cache.*`).
 * - Do **not** reintroduce an OIDF `ScopedCache` / `CacheManager` facade.
 * - Prefer portable serializers ([CacheSerializers.string], [CacheSerializers.json],
 *   domain serializers). [IdentityCacheSerializer] is **local-only** and must not
 *   be used when a distributed backend may serve the namespace.
 */
object OidfCache {
    private val logger = Log.app().withTag("sphereon:oidf:cache")

    /**
     * Create an IDK [CacheManager] with a platform-default local backend.
     * Useful for tests and standalone clients; servers should inject IDK's DI-bound manager.
     */
    fun newManager(): CacheManager {
        val manager = DefaultCacheManager()
        val backend = createDefaultCacheBackend()
        manager.registerBackend(backend)
        logger.debug("Created standalone CacheManager with backend=${backend.id}")
        return manager
    }

    /**
     * Create (or return existing) a typed cache.
     *
     * Callers must pass serializers appropriate for the deployment:
     * portable for distributed, [IdentityCacheSerializer] only for in-process.
     */
    fun <K : Any, V : Any> createTypedCache(
        manager: CacheManager,
        requirements: CacheRequirements,
        keySerializer: CacheSerializer<K>,
        valueSerializer: CacheSerializer<V>,
    ): ScopedCache<K, V> =
        manager.createCache(
            requirements = requirements,
            keySerializer = keySerializer,
            valueSerializer = valueSerializer,
        )

    /**
     * String-key convenience. Prefer [CacheSerializers.json] / domain serializers for [V]
     * when `requirements` allow distributed backends.
     */
    fun <V : Any> createStringKeyCache(
        manager: CacheManager,
        requirements: CacheRequirements,
        valueSerializer: CacheSerializer<V>,
    ): ScopedCache<String, V> =
        manager.createCache(
            requirements = requirements,
            keySerializer = CacheSerializers.string,
            valueSerializer = valueSerializer,
        )

    /**
     * Local-only typed cache (identity serializers). Fails hard if requirements ask for
     * distributed-only locality — use portable serializers instead.
     */
    fun <K : Any, V : Any> createLocalOnlyTypedCache(
        manager: CacheManager,
        requirements: CacheRequirements,
    ): ScopedCache<K, V> {
        require(requirements.locality != com.sphereon.core.api.cache.CacheLocality.DISTRIBUTED_ONLY) {
            "createLocalOnlyTypedCache cannot be used with DISTRIBUTED_ONLY (namespace=${requirements.namespace})"
        }
        if (requirements.distributedFallback || requirements.writeThrough) {
            logger.warn(
                "Identity serializers used with distributedFallback/writeThrough " +
                    "(namespace=${requirements.namespace}); values will not round-trip across nodes",
            )
        }
        return createTypedCache(
            manager = manager,
            requirements = requirements,
            keySerializer = IdentityCacheSerializer(),
            valueSerializer = IdentityCacheSerializer(),
        )
    }
}

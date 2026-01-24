package com.sphereon.openid.fed.core.cache

/**
 * Defines where cache data should be stored and accessed from.
 */
enum class CacheLocality {
    /**
     * Use local (in-memory) cache only.
     * Fast access, not shared across instances.
     */
    LOCAL_ONLY,

    /**
     * Prefer local cache, fall back to distributed if configured.
     * Best for most use cases - fast local access with optional shared state.
     */
    LOCAL_PREFERRED,

    /**
     * Use distributed cache only (e.g., Redis).
     * Shared across all instances, slightly slower access.
     */
    DISTRIBUTED_ONLY,

    /**
     * Use distributed cache with local read-through cache.
     * Shared state with local caching for read performance.
     */
    DISTRIBUTED_WITH_LOCAL
}

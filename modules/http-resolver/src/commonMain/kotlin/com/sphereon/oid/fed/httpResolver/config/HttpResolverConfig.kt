package com.sphereon.oid.fed.httpResolver.config

import com.sphereon.oid.fed.cache.CacheConfig

interface HttpResolverConfig {
    // HTTP settings
    val enableHttpCaching: Boolean
    val enableEtagSupport: Boolean
    val httpTimeoutMs: Long
    val httpRetries: Int

    // Cache configuration
    val cacheConfig: CacheConfig
}
package com.sphereon.oid.fed.httpResolver.config

import com.sphereon.oid.fed.cache.CacheConfig

data class DefaultHttpResolverConfig(
    override val enableHttpCaching: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_HTTP_CACHING,
    override val enableEtagSupport: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_ETAG_SUPPORT,
    override val httpTimeoutMs: Long = HttpResolverDefaults.DEFAULT_HTTP_TIMEOUT_MS,
    override val httpRetries: Int = HttpResolverDefaults.DEFAULT_HTTP_RETRIES,

    override val cacheConfig: CacheConfig = CacheConfig()
) : HttpResolverConfig
package com.sphereon.openid.fed.httpResolver.config

/**
 * Implementation of the `HttpResolverConfig` interface, providing default configuration
 * options for HTTP resolution.
 *
 * This data class encapsulates HTTP settings used for fetching remote resources,
 * retry logic, and controlling HTTP behavior.
 *
 * Note: Cache configuration (TTL, max size, etc.) is now managed by the
 * CacheRequirements and CacheManager infrastructure in core-impl.
 */
data class DefaultHttpResolverConfig(
    override val enableHttpCaching: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_HTTP_CACHING,
    override val enableEtagSupport: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_ETAG_SUPPORT,
    override val httpTimeoutMs: Long = HttpResolverDefaults.DEFAULT_HTTP_TIMEOUT_MS,
    override val httpRetries: Int = HttpResolverDefaults.DEFAULT_HTTP_RETRIES
) : HttpResolverConfig

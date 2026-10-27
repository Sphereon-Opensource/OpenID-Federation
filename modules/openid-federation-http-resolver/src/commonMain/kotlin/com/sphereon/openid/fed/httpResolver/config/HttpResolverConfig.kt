package com.sphereon.openid.fed.httpResolver.config

/**
 * Interface defining the configuration options for HTTP resolution.
 *
 * This configuration governs various aspects of HTTP requests, including
 * connection settings, caching mechanisms, and retry logic.
 *
 * Properties:
 * - HTTP settings:
 *   - `enableHttpCaching`: Indicates whether HTTP response caching is enabled.
 *   - `enableEtagSupport`: Specifies if ETag support is enabled for conditional requests.
 *   - `httpTimeoutMs`: Configures the timeout duration for HTTP requests, in milliseconds.
 *   - `httpRetries`: Determines the number of retry attempts for failed HTTP requests.
 *   - `maxStatementReuseSeconds`: Longest time a typed federation JWT is served from the cache, on top of its signed
 *     `exp` and the response's `Cache-Control` lifetime. Null keeps only those two bounds.
 *
 * Note: Cache configuration (TTL, max size, etc.) is managed by IDK
 * [com.sphereon.core.api.cache.CacheRequirements] / [com.sphereon.core.api.cache.CacheManager].
 */
interface HttpResolverConfig {
    // HTTP settings
    val enableHttpCaching: Boolean
    val enableEtagSupport: Boolean
    val httpTimeoutMs: Long
    val httpRetries: Int

    /**
     * How long this participant reuses a cached Entity Statement before fetching it again (OpenID Federation 1.1 §11:
     * how often a participant reevaluates depends on how quickly it wants to find out that something has changed).
     */
    val maxStatementReuseSeconds: Long? get() = null
}

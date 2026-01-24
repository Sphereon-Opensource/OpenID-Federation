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
 *
 * Note: Cache configuration (TTL, max size, etc.) is now managed by the
 * CacheRequirements and CacheManager infrastructure in core-impl.
 */
interface HttpResolverConfig {
    // HTTP settings
    val enableHttpCaching: Boolean
    val enableEtagSupport: Boolean
    val httpTimeoutMs: Long
    val httpRetries: Int
}

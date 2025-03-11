package com.sphereon.oid.fed.httpResolver.config

import com.sphereon.oid.fed.cache.CacheConfig

/**
 * Interface defining the configuration options for HTTP resolution.
 *
 * This configuration governs various aspects of HTTP requests, including
 * connection settings, caching mechanisms, and retry logic. It provides
 * flexibility for HTTP operations such as enabling caching and specifying
 * timeouts or retry behavior.
 *
 * Properties:
 * - HTTP settings:
 *   - `enableHttpCaching`: Indicates whether HTTP response caching is enabled.
 *   - `enableEtagSupport`: Specifies if ETag support is enabled for conditional requests.
 *   - `httpTimeoutMs`: Configures the timeout duration for HTTP requests, in milliseconds.
 *   - `httpRetries`: Determines the number of retry attempts for failed HTTP requests.
 * - Cache configuration:
 *   - `cacheConfig`: Provides detailed cache settings, such as size, expiration policies,
 *     and optional persistent storage configuration via the `CacheConfig` object.
 */
interface HttpResolverConfig {
    // HTTP settings
    val enableHttpCaching: Boolean
    val enableEtagSupport: Boolean
    val httpTimeoutMs: Long
    val httpRetries: Int

    // Cache configuration
    val cacheConfig: CacheConfig
}
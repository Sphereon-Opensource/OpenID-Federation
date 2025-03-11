package com.sphereon.oid.fed.httpResolver.config

import com.sphereon.oid.fed.cache.CacheConfig

/**
 * Implementation of the `HttpResolverConfig` interface, providing default configuration
 * options for HTTP resolution.
 *
 * This data class encapsulates various HTTP settings and caching configurations that
 * are used in HTTP operations such as fetching remote resources, retry logic, and
 * controlling cache behavior.
 *
 * The following properties override the defaults defined in `HttpResolverDefaults`:
 *
 * - `enableHttpCaching`: Determines whether HTTP response caching is enabled.
 * - `enableEtagSupport`: Specifies if ETag support is enabled for conditional HTTP requests.
 * - `httpTimeoutMs`: Configures the timeout duration for HTTP requests in milliseconds.
 * - `httpRetries`: Indicates the number of retry attempts for failed HTTP requests.
 * - `cacheConfig`: Specifies the caching behavior and policies using the `CacheConfig` object.
 *
 * This class provides a flexible default configuration for clients to use when performing
 * HTTP operations, while still allowing the ability to override these defaults as needed.
 */
data class DefaultHttpResolverConfig(
    override val enableHttpCaching: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_HTTP_CACHING,
    override val enableEtagSupport: Boolean = HttpResolverDefaults.DEFAULT_ENABLE_ETAG_SUPPORT,
    override val httpTimeoutMs: Long = HttpResolverDefaults.DEFAULT_HTTP_TIMEOUT_MS,
    override val httpRetries: Int = HttpResolverDefaults.DEFAULT_HTTP_RETRIES,

    override val cacheConfig: CacheConfig = CacheConfig()
) : HttpResolverConfig
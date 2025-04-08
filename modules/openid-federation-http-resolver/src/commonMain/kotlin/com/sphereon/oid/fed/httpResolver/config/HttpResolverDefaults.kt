package com.sphereon.oid.fed.httpResolver.config

/**
 * Default values for HTTP resolver configuration settings.
 *
 * This object contains constant values representing defaults for various HTTP settings
 * used in the `HttpResolverConfig` and its implementations. These defaults provide
 * a baseline configuration for HTTP operations.
 *
 * Constants:
 *
 * - `DEFAULT_HTTP_TIMEOUT_MS`: The default timeout for HTTP requests, in milliseconds (30,000 ms).
 * - `DEFAULT_HTTP_RETRIES`: The default number of retry attempts for failed HTTP requests (3 retries).
 * - `DEFAULT_ENABLE_HTTP_CACHING`: Indicates whether HTTP response caching is enabled by default (true).
 * - `DEFAULT_ENABLE_ETAG_SUPPORT`: Specifies if ETag support for conditional requests is enabled by default (true).
 */
object HttpResolverDefaults {
    const val DEFAULT_HTTP_TIMEOUT_MS = 30000L
    const val DEFAULT_HTTP_RETRIES = 3
    const val DEFAULT_ENABLE_HTTP_CACHING = true
    const val DEFAULT_ENABLE_ETAG_SUPPORT = true
}
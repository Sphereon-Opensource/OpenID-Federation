package com.sphereon.oid.fed.httpResolver

import com.sphereon.oid.fed.cache.Cache
import com.sphereon.oid.fed.cache.CacheOptions
import com.sphereon.oid.fed.cache.CacheStrategy
import com.sphereon.oid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.oid.fed.httpResolver.config.HttpResolverConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay

private val logger = HttpResolverConst.LOG

/**
 * A utility class for resolving HTTP resources with optional caching and retry capabilities.
 * This resolver fetches data from remote URLs, supports conditional request headers for caching
 * (such as ETag and Last-Modified), and allows for different cache strategies such as using the cache
 * only, forcing a remote fetch, or preferring cached data.
 *
 * @param V The type of the response value.
 * @param config Configuration for the HTTP resolver, including caching, timeout, and retry settings.
 * @param httpClient The HTTP client used for making requests.
 * @param cache A cache instance for storing and retrieving HTTP metadata and values.
 * @param responseMapper A suspend function to map an [HttpResponse] object to the desired value type [V].
 */
class HttpResolver<V : Any>(
    private val config: HttpResolverConfig = DefaultHttpResolverConfig(),
    private val httpClient: HttpClient,
    private val cache: Cache<String, HttpMetadata<V>>,
    private val responseMapper: suspend (HttpResponse) -> V
) {
    /**
     * Adds conditional cache headers to the HTTP request based on the provided URL
     * and cache configuration. The method checks the cache for existing metadata
     * (e.g., ETag or last modified date) of the URL and appends them as headers
     * if available and supported.
     *
     * @param url The URL of the resource for which cache headers should be added.
     */
// Extracted helper function to add conditional cache headers.
    private fun HttpRequestBuilder.applyCacheHeaders(url: String) {
        if (config.enableHttpCaching) {
            headers {
                cache.getIfAvailable(url, CacheOptions())?.let { metadata ->
                    metadata.etag?.let { etag ->
                        logger.debug("Adding If-None-Match header: $etag")
                        append(HttpHeaders.IfNoneMatch, etag)
                    }
                    metadata.lastModified?.let { lastModified ->
                        logger.debug("Adding If-Modified-Since header: $lastModified")
                        append(HttpHeaders.IfModifiedSince, lastModified)
                    }
                }
            }
        }
    }

    /**
     * Executes an HTTP GET request to the specified URL with retry mechanisms.
     * This method attempts the request multiple times based on the configured retry count,
     * applying a backoff delay between attempts when failures occur. Additionally, it applies
     * cache-related headers if caching is enabled.
     *
     * @param url The target URL for the HTTP GET request.
     * @param attempt The current attempt number, used to track retries (default is 1).
     * @return The HTTP response obtained from a successful request.
     * @throws Exception If all retry attempts fail.
     */
    private suspend fun fetchWithRetry(url: String, attempt: Int = 1): HttpResponse {
        try {
            logger.debug("Attempting HTTP request for $url (attempt $attempt/${config.httpRetries})")
            return httpClient.get(url) {
                applyCacheHeaders(url)
                timeout {
                    requestTimeoutMillis = config.httpTimeoutMs
                }
            }
        } catch (e: Exception) {
            if (attempt < config.httpRetries) {
                val retryDelayMs = 1000L * (1 shl (attempt - 1))
                logger.warn(
                    "HTTP request failed for $url, retrying in ${retryDelayMs}ms (attempt $attempt): ${e.message ?: "Unknown error"}"
                )
                delay(retryDelayMs)
                return fetchWithRetry(url, attempt + 1)
            }
            logger.error("HTTP request failed for $url after $attempt attempts", e)
            throw e
        }
    }

    /**
     * Fetches content from a remote URL and returns it as `HttpMetadata`.
     * Performs the HTTP operation with retry logic and processes the response
     * using a response mapper to extract the desired value.
     *
     * @param url The URL to fetch the resource from.
     * @return An `HttpMetadata<V>` object containing the retrieved value,
     *         and optionally the ETag and last modified headers if HTTP caching is enabled.
     */
    private suspend fun fetchFromRemote(url: String): HttpMetadata<V> {
        logger.debug("Fetching from remote: $url")
        val response = fetchWithRetry(url)
        val value = responseMapper(response)
        return HttpMetadata(
            value = value,
            etag = if (config.enableHttpCaching) response.headers[HttpHeaders.ETag] else null,
            lastModified = if (config.enableHttpCaching) response.headers[HttpHeaders.LastModified] else null
        )
    }

    /**
     * Fetches the resource located at the specified URL using the cache strategy provided in the options.
     *
     * @param url The URL of the resource to retrieve.
     * @param options The caching strategy to use for fetching the resource. Defaults to CacheOptions with a CACHE_FIRST strategy.
     * @return The resource content as a string, retrieved either from the cache or the remote source based on the specified strategy.
     * @throws IllegalStateException Thrown when no cached value is available for CACHE_ONLY strategy or when the operation fails for other strategies.
     */
    suspend fun get(url: String, options: CacheOptions = CacheOptions()): String {
        logger.debug("Retrieving resource from $url using strategy ${options.strategy}")
        return when (options.strategy) {
            CacheStrategy.CACHE_ONLY -> {
                logger.debug("Using cache-only strategy for $url")
                cache.getIfAvailable(url, options)?.value?.toString()
                    ?: throw IllegalStateException("No cached value available")
            }

            CacheStrategy.FORCE_REMOTE -> {
                logger.debug("Using force-remote strategy for $url")
                val metadata = fetchFromRemote(url)
                cache.put(url) { metadata }
                metadata.value.toString()
            }

            CacheStrategy.CACHE_FIRST -> {
                logger.debug("Using cache-first strategy for $url")
                cache.getOrPut(url, { fetchFromRemote(url) }, options)?.value?.toString()
                    ?: throw IllegalStateException("Failed to get or put value in cache")
            }
        }
    }
}
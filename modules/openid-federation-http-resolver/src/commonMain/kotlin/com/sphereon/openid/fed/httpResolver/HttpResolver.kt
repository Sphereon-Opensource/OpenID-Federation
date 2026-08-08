package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheScope
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.openid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.openid.fed.httpResolver.config.HttpResolverConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay

private val logger = HttpResolverConst.LOG

/**
 * A utility class for resolving HTTP resources with scoped caching and retry capabilities.
 *
 * This resolver fetches data from remote URLs, supports conditional request headers for caching
 * (such as ETag and Last-Modified), and uses IDK [ScopedCache] for multi-tenant cache isolation.
 *
 * Cache Scoping:
 * - APP scope: Used for shared resources like trust anchor configurations
 * - TENANT scope: Used for per-account entity configurations
 *
 * @param V The type of the response value.
 * @param config Configuration for the HTTP resolver, including timeout and retry settings.
 * @param httpClient The HTTP client used for making requests.
 * @param cache An IDK [ScopedCache] for storing and retrieving HTTP metadata.
 * @param responseMapper A suspend function to map an [HttpResponse] object to the desired value type [V].
 */
class HttpResolver<V : Any>(
    private val config: HttpResolverConfig = DefaultHttpResolverConfig(),
    private val httpClient: HttpClient,
    private val cache: ScopedCache<String, HttpMetadata<V>>,
    private val responseMapper: suspend (HttpResponse) -> V
) {
    /**
     * Adds conditional cache headers to the HTTP request based on cached metadata.
     * Uses the APP scope to check for existing cache entries.
     */
    private fun HttpRequestBuilder.applyCacheHeaders(url: String, cachedMetadata: HttpMetadata<V>?) {
        if (config.enableHttpCaching && cachedMetadata != null) {
            headers {
                cachedMetadata.etag?.let { etag ->
                    logger.debug("Adding If-None-Match header: $etag")
                    append(HttpHeaders.IfNoneMatch, etag)
                }
                cachedMetadata.lastModified?.let { lastModified ->
                    logger.debug("Adding If-Modified-Since header: $lastModified")
                    append(HttpHeaders.IfModifiedSince, lastModified)
                }
            }
        }
    }

    /**
     * Executes an HTTP GET request with retry mechanisms and exponential backoff.
     */
    private suspend fun fetchWithRetry(
        url: String,
        cachedMetadata: HttpMetadata<V>?,
        attempt: Int = 1
    ): HttpResponse {
        try {
            logger.debug("Attempting HTTP request for $url (attempt $attempt/${config.httpRetries})")
            return httpClient.get(url) {
                applyCacheHeaders(url, cachedMetadata)
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
                return fetchWithRetry(url, cachedMetadata, attempt + 1)
            }
            logger.error("HTTP request failed for $url after $attempt attempts", e)
            throw e
        }
    }

    /**
     * Fetches content from a remote URL and returns it as `HttpMetadata`.
     */
    private suspend fun fetchFromRemote(url: String, cachedMetadata: HttpMetadata<V>?): HttpMetadata<V> {
        logger.debug("Fetching from remote: $url")
        val response = fetchWithRetry(url, cachedMetadata)

        // Handle 304 Not Modified - return cached value
        if (response.status == HttpStatusCode.NotModified && cachedMetadata != null) {
            logger.debug("Received 304 Not Modified, using cached value for $url")
            return cachedMetadata
        }

        val value = responseMapper(response)
        return HttpMetadata(
            value = value,
            etag = if (config.enableHttpCaching) response.headers[HttpHeaders.ETag] else null,
            lastModified = if (config.enableHttpCaching) response.headers[HttpHeaders.LastModified] else null
        )
    }

    // ========== APP-scoped operations (shared across all tenants) ==========

    /**
     * Resolve a URL using APP scope (shared across all tenants).
     *
     * Use this for trust anchor configurations and other shared resources.
     * The cache-first strategy is used: check cache first, fetch if not found.
     *
     * @param url The URL of the resource to retrieve.
     * @return The resource content as a string.
     */
    suspend fun get(url: String): String {
        logger.debug("Retrieving resource from $url (APP scope)")

        val cached = cache.getApp(url)
        if (cached != null) {
            logger.debug("Cache hit for $url (APP scope)")
            return cached.value.toString()
        }

        logger.debug("Cache miss for $url (APP scope), fetching from remote")
        val metadata = fetchFromRemote(url, null)
        cache.putApp(url, metadata)
        return metadata.value.toString()
    }

    /**
     * Resolve a URL using APP scope, with optional cache bypass.
     *
     * @param url The URL of the resource to retrieve.
     * @param forceRefresh If true, bypass cache and fetch from remote.
     * @return The resource content as a string.
     */
    suspend fun get(url: String, forceRefresh: Boolean): String {
        if (!forceRefresh) {
            return get(url)
        }

        logger.debug("Force refresh for $url (APP scope)")
        val cached = cache.getApp(url)
        val metadata = fetchFromRemote(url, cached)
        cache.putApp(url, metadata)
        return metadata.value.toString()
    }

    /**
     * Get cached value only from APP scope (no remote fetch).
     *
     * @param url The URL of the resource.
     * @return The cached value, or null if not cached.
     */
    suspend fun getCachedApp(url: String): String? {
        return cache.getApp(url)?.value?.toString()
    }

    // ========== TENANT-scoped operations (per-account isolation) ==========

    /**
     * Resolve a URL using TENANT scope (per-account isolation).
     *
     * Use this for per-account entity configurations.
     * The cache-first strategy is used: check cache first, fetch if not found.
     *
     * @param url The URL of the resource to retrieve.
     * @param tenantId The tenant identifier for cache isolation.
     * @return The resource content as a string.
     */
    suspend fun getForTenant(url: String, tenantId: String): String {
        logger.debug("Retrieving resource from $url (TENANT scope: $tenantId)")

        val cached = cache.getTenant(tenantId, url)
        if (cached != null) {
            logger.debug("Cache hit for $url (TENANT scope: $tenantId)")
            return cached.value.toString()
        }

        logger.debug("Cache miss for $url (TENANT scope: $tenantId), fetching from remote")
        val metadata = fetchFromRemote(url, null)
        cache.putTenant(tenantId, url, metadata)
        return metadata.value.toString()
    }

    /**
     * Resolve a URL using TENANT scope, with optional cache bypass.
     *
     * @param url The URL of the resource to retrieve.
     * @param tenantId The tenant identifier for cache isolation.
     * @param forceRefresh If true, bypass cache and fetch from remote.
     * @return The resource content as a string.
     */
    suspend fun getForTenant(url: String, tenantId: String, forceRefresh: Boolean): String {
        if (!forceRefresh) {
            return getForTenant(url, tenantId)
        }

        logger.debug("Force refresh for $url (TENANT scope: $tenantId)")
        val cached = cache.getTenant(tenantId, url)
        val metadata = fetchFromRemote(url, cached)
        cache.putTenant(tenantId, url, metadata)
        return metadata.value.toString()
    }

    /**
     * Get cached value only from TENANT scope (no remote fetch).
     *
     * @param url The URL of the resource.
     * @param tenantId The tenant identifier for cache isolation.
     * @return The cached value, or null if not cached.
     */
    suspend fun getCachedTenant(url: String, tenantId: String): String? {
        return cache.getTenant(tenantId, url)?.value?.toString()
    }

    // ========== Generic scoped operations ==========

    /**
     * Resolve a URL using the specified scope.
     *
     * @param url The URL of the resource to retrieve.
     * @param scope The IDK cache scope to use.
     * @param scopeId The scope identifier (required for TENANT and PRINCIPAL scopes).
     * @param forceRefresh If true, bypass cache and fetch from remote.
     * @return The resource content as a string.
     */
    suspend fun get(
        url: String,
        scope: CacheScope,
        scopeId: String? = null,
        forceRefresh: Boolean = false
    ): String {
        return when (scope) {
            CacheScope.APP -> get(url, forceRefresh)
            CacheScope.TENANT -> getForTenant(url, scopeId!!, forceRefresh)
            CacheScope.PRINCIPAL -> {
                // PRINCIPAL uses TENANT isolation path (tenantId = principal id) for now
                logger.debug("PRINCIPAL scope using TENANT implementation for $url")
                getForTenant(url, scopeId!!, forceRefresh)
            }
        }
    }

    // ========== Cache management ==========

    /**
     * Clear all cached entries.
     */
    suspend fun clearCache() {
        cache.clear()
        logger.debug("Cleared all HTTP resolver cache entries")
    }

    /**
     * Clear cached entries for a specific tenant.
     */
    suspend fun clearCacheForTenant(tenantId: String) {
        cache.invalidateTenant(tenantId)
        logger.debug("Cleared HTTP resolver cache for tenant: $tenantId")
    }
}

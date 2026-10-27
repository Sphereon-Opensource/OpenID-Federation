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
import kotlinx.coroutines.CancellationException

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
    private val responseMapper: suspend (HttpResponse) -> V,
) {
    private var validateUrl: ((String) -> Unit)? = null
    private var cacheNamespace: String? = null
    private var requestPolicyInstalled: Boolean = false

    /** Current time in epoch seconds; tests replace it to move past a cached document's freshness bound. */
    internal var epochSeconds: () -> Long = { currentEpochSeconds() }

    /** Installs the immutable request-policy identity and URL check before sharing this resolver with callers. */
    fun installRequestPolicy(
        cacheNamespace: String,
        validator: (String) -> Unit,
    ) {
        check(!requestPolicyInstalled) { "A request policy is already installed" }
        require(cacheNamespace.isNotBlank() && cacheNamespace.length <= 16_384) {
            "The cache namespace must be non-empty and at most 16384 characters"
        }
        this.cacheNamespace = cacheNamespace
        validateUrl = validator
        requestPolicyInstalled = true
    }

    private fun validate(url: String) {
        validateUrl?.invoke(url)
    }

    private fun cacheKey(url: String): String = cacheNamespace?.let { namespace -> "ns:${namespace.length}:$namespace|url:${url.length}:$url" } ?: url
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
    private suspend fun <R> fetchWithRetry(
        url: String,
        cachedMetadata: HttpMetadata<V>?,
        attempt: Int = 1,
        expectedContentType: String? = null,
        mapResponse: suspend (HttpResponse) -> R,
    ): R {
        try {
            logger.debug("Attempting HTTP request for $url (attempt $attempt/${config.httpRetries})")
            return httpClient.prepareGet(url) {
                applyCacheHeaders(url, cachedMetadata)
                expectedContentType?.let { header(HttpHeaders.Accept, it) }
                timeout {
                    requestTimeoutMillis = config.httpTimeoutMs
                }
            }.execute(mapResponse)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is FederationHttpFetchException) throw e
            if (attempt < config.httpRetries) {
                val retryDelayMs = 1000L * (1 shl (attempt - 1))
                logger.warn(
                    "HTTP request failed for $url, retrying in ${retryDelayMs}ms (attempt $attempt): ${e.message ?: "Unknown error"}"
                )
                delay(retryDelayMs)
                return fetchWithRetry(url, cachedMetadata, attempt + 1, expectedContentType, mapResponse)
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
        return fetchWithRetry(url, cachedMetadata) { response ->
            if (response.status == HttpStatusCode.NotModified && cachedMetadata != null) cachedMetadata
            else HttpMetadata(
                value = responseMapper(response),
                etag = if (config.enableHttpCaching) response.headers[HttpHeaders.ETag] else null,
                lastModified = if (config.enableHttpCaching) response.headers[HttpHeaders.LastModified] else null,
            )
        }
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
        validate(url)
        logger.debug("Retrieving resource from $url (APP scope)")

        val cached = cache.getApp(cacheKey(url))
        if (cached != null) {
            logger.debug("Cache hit for $url (APP scope)")
            return cached.value.toString()
        }

        logger.debug("Cache miss for $url (APP scope), fetching from remote")
        val metadata = fetchFromRemote(url, null)
        cache.putApp(cacheKey(url), metadata)
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
        validate(url)
        if (!forceRefresh) {
            return get(url)
        }

        logger.debug("Force refresh for $url (APP scope)")
        val cached = cache.getApp(cacheKey(url))
        val metadata = fetchFromRemote(url, cached)
        cache.putApp(cacheKey(url), metadata)
        return metadata.value.toString()
    }

    /** Fetch a typed Entity Statement; bypassing skips cached bodies and conditional headers. */
    suspend fun getJwt(url: String, bypassCache: Boolean = false): String =
        getTypedJwt(url, ENTITY_STATEMENT_CONTENT_TYPE, bypassCache)

    /**
     * Other signed federation documents retain their own media type without changing getJwt's public ABI.
     *
     * A cached document is served only until the earliest of its signed `exp`, the response's `Cache-Control`
     * freshness lifetime and [HttpResolverConfig.maxStatementReuseSeconds], so a statement is never returned past its
     * `exp` and a superior's re-signed statement is picked up within that reuse limit. Documents without a usable `exp`, already
     * expired, or marked `no-store`/`no-cache` are not cached. [bypassCache] skips cached bodies and conditional
     * headers and replaces the cached entry with the freshly fetched document, so later cached reads see it too.
     */
    suspend fun getTypedJwt(url: String, expectedContentType: String, bypassCache: Boolean = false): String {
        validate(url)
        val key = cacheKey(url)
        if (!bypassCache) {
            val cached = cache.getApp(key)
            val freshUntil = cached?.expiresAtEpochSeconds
            if (cached?.jwtContentType == expectedContentType && freshUntil != null && freshUntil > epochSeconds()) {
                return cached.value.toString()
            }
        }
        var cacheControl: String? = null
        var age: String? = null
        val value = try {
            fetchWithRetry(url, null, expectedContentType = expectedContentType) { response ->
                validateJwtResponse(response, expectedContentType)
                cacheControl = response.headers.getAll(HttpHeaders.CacheControl)?.joinToString(",")
                age = response.headers[HttpHeaders.Age]
                responseMapper(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FederationHttpFetchException("Failed to fetch $url: ${e.message}", e)
        }
        if (value.toString().encodeToByteArray().size > MAX_JWT_BYTES) {
            throw FederationHttpFetchException("JWT response from $url exceeds $MAX_JWT_BYTES bytes")
        }
        val now = epochSeconds()
        val freshUntil = typedJwtFreshUntil(value.toString(), cacheControl, age, now, config.maxStatementReuseSeconds)
        if (freshUntil != null && freshUntil > now) {
            cache.putApp(key, HttpMetadata(value, jwtContentType = expectedContentType, expiresAtEpochSeconds = freshUntil))
        } else {
            // Never leave an older entry behind for a document that may not be served from the cache.
            cache.removeApp(key)
        }
        return value.toString()
    }

    /** Removes the cached typed or untyped APP entry for [url], e.g. after the co-located issuer republished it. */
    suspend fun invalidate(url: String) {
        validate(url)
        cache.removeApp(cacheKey(url))
    }

    /** Query a Trust Mark status endpoint through the same protected HTTP client. */
    suspend fun postJwt(url: String, formParameters: Parameters): String {
        validate(url)
        val value = try {
            httpClient.preparePost(url) {
                setBody(io.ktor.client.request.forms.FormDataContent(formParameters))
                timeout { requestTimeoutMillis = config.httpTimeoutMs }
            }.execute { response ->
                validateJwtResponse(response, "application/trust-mark-status-response+jwt")
                responseMapper(response).toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FederationHttpFetchException("Failed to query $url: ${e.message}", e)
        }
        if (value.encodeToByteArray().size > MAX_JWT_BYTES) {
            throw FederationHttpFetchException("JWT response from $url exceeds $MAX_JWT_BYTES bytes")
        }
        return value
    }

    private fun validateJwtResponse(response: HttpResponse, expectedContentType: String) {
        if (response.status != HttpStatusCode.OK) {
            throw FederationHttpFetchException("Expected HTTP 200, received ${response.status.value}")
        }
        val contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
        if (!expectedContentType.equals(contentType, ignoreCase = true)) {
            throw FederationHttpFetchException("Expected $expectedContentType, received ${contentType ?: "no content type"}")
        }
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > MAX_JWT_BYTES) {
            throw FederationHttpFetchException("JWT response exceeds $MAX_JWT_BYTES bytes")
        }
    }

    private companion object {
        const val ENTITY_STATEMENT_CONTENT_TYPE = "application/entity-statement+jwt"
        const val MAX_JWT_BYTES = 1_048_576
    }

    /**
     * Get cached value only from APP scope (no remote fetch).
     *
     * @param url The URL of the resource.
     * @return The cached value, or null if not cached.
     */
    suspend fun getCachedApp(url: String): String? {
        validate(url)
        return cache.getApp(cacheKey(url))?.value?.toString()
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
        validate(url)
        logger.debug("Retrieving resource from $url (TENANT scope: $tenantId)")

        val cached = cache.getTenant(tenantId, cacheKey(url))
        if (cached != null) {
            logger.debug("Cache hit for $url (TENANT scope: $tenantId)")
            return cached.value.toString()
        }

        logger.debug("Cache miss for $url (TENANT scope: $tenantId), fetching from remote")
        val metadata = fetchFromRemote(url, null)
        cache.putTenant(tenantId, cacheKey(url), metadata)
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
        validate(url)
        if (!forceRefresh) {
            return getForTenant(url, tenantId)
        }

        logger.debug("Force refresh for $url (TENANT scope: $tenantId)")
        val cached = cache.getTenant(tenantId, cacheKey(url))
        val metadata = fetchFromRemote(url, cached)
        cache.putTenant(tenantId, cacheKey(url), metadata)
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
        validate(url)
        return cache.getTenant(tenantId, cacheKey(url))?.value?.toString()
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

/** A remote resource could not supply a successful, typed JWT response. */
class FederationHttpFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

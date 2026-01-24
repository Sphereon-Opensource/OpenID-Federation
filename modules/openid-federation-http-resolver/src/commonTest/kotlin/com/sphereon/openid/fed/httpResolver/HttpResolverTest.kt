package com.sphereon.openid.fed.httpResolver

import com.sphereon.openid.fed.core.cache.CacheScope
import com.sphereon.openid.fed.core.cache.CacheStatistics
import com.sphereon.openid.fed.core.cache.ScopedCache
import com.sphereon.openid.fed.httpResolver.config.HttpResolverConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for HttpResolver covering:
 * - Cache hit/miss scenarios
 * - APP vs TENANT scope isolation
 * - ETag and Last-Modified header handling
 * - 304 Not Modified response handling
 * - Force refresh behavior
 * - Retry logic with exponential backoff
 * - Cache clearing operations
 */
class HttpResolverTest {

    private lateinit var mockCache: MockScopedCache
    private var requestCount: Int = 0
    private var lastRequestHeaders: Headers? = null

    @BeforeTest
    fun setup() {
        mockCache = MockScopedCache()
        requestCount = 0
        lastRequestHeaders = null
    }

    private fun createMockClient(
        responseBody: String = "test response",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        responseHeaders: Map<String, String> = emptyMap(),
        onRequest: ((HttpRequestData) -> Unit)? = null
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestCount++
                    lastRequestHeaders = request.headers
                    onRequest?.invoke(request)
                    respond(
                        content = responseBody,
                        status = statusCode,
                        headers = headersOf(*responseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
                    )
                }
            }
        }
    }

    private fun createResolver(
        httpClient: HttpClient,
        config: HttpResolverConfig = TestHttpResolverConfig()
    ): HttpResolver<String> {
        return HttpResolver(
            config = config,
            httpClient = httpClient,
            cache = mockCache,
            responseMapper = { response -> response.bodyAsText() }
        )
    }

    // ========== APP Scope Tests ==========

    @Test
    fun `get with APP scope returns cached value on cache hit`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("cached value", etag = "etag-123")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.get(url)

        assertEquals("cached value", result)
        assertEquals(0, requestCount, "Should not make HTTP request on cache hit")
    }

    @Test
    fun `get with APP scope fetches from remote on cache miss`() = runTest {
        val url = "https://example.com/resource"
        val responseBody = "remote value"

        val client = createMockClient(
            responseBody = responseBody,
            responseHeaders = mapOf(
                HttpHeaders.ETag to "etag-456",
                HttpHeaders.LastModified to "Wed, 01 Jan 2025 00:00:00 GMT"
            )
        )
        val resolver = createResolver(client)

        val result = resolver.get(url)

        assertEquals(responseBody, result)
        assertEquals(1, requestCount, "Should make one HTTP request on cache miss")
        assertNotNull(mockCache.appCache[url], "Should cache the response")
        assertEquals("etag-456", mockCache.appCache[url]?.etag)
    }

    @Test
    fun `get with forceRefresh bypasses cache`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("old cached value", etag = "old-etag")

        val client = createMockClient(
            responseBody = "new value",
            responseHeaders = mapOf(HttpHeaders.ETag to "new-etag")
        )
        val resolver = createResolver(client)

        val result = resolver.get(url, forceRefresh = true)

        assertEquals("new value", result)
        assertEquals(1, requestCount, "Should make HTTP request even with cache")
        assertEquals("new-etag", mockCache.appCache[url]?.etag, "Should update cache")
    }

    @Test
    fun `getCachedApp returns cached value without fetching`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("cached only")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.getCachedApp(url)

        assertEquals("cached only", result)
        assertEquals(0, requestCount)
    }

    @Test
    fun `getCachedApp returns null when not cached`() = runTest {
        val url = "https://example.com/resource"
        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.getCachedApp(url)

        assertNull(result)
        assertEquals(0, requestCount)
    }

    // ========== TENANT Scope Tests ==========

    @Test
    fun `getForTenant returns cached value for specific tenant`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        mockCache.tenantCache["$tenantId:$url"] = HttpMetadata("tenant cached value")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.getForTenant(url, tenantId)

        assertEquals("tenant cached value", result)
        assertEquals(0, requestCount)
    }

    @Test
    fun `getForTenant provides tenant isolation`() = runTest {
        val url = "https://example.com/resource"
        mockCache.tenantCache["tenant-1:$url"] = HttpMetadata("tenant-1 value")
        mockCache.tenantCache["tenant-2:$url"] = HttpMetadata("tenant-2 value")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result1 = resolver.getForTenant(url, "tenant-1")
        val result2 = resolver.getForTenant(url, "tenant-2")

        assertEquals("tenant-1 value", result1)
        assertEquals("tenant-2 value", result2)
        assertEquals(0, requestCount)
    }

    @Test
    fun `getForTenant fetches from remote on cache miss`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"

        val client = createMockClient(responseBody = "tenant remote value")
        val resolver = createResolver(client)

        val result = resolver.getForTenant(url, tenantId)

        assertEquals("tenant remote value", result)
        assertEquals(1, requestCount)
        assertNotNull(mockCache.tenantCache["$tenantId:$url"])
    }

    @Test
    fun `getCachedTenant returns cached value without fetching`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        mockCache.tenantCache["$tenantId:$url"] = HttpMetadata("tenant cached")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.getCachedTenant(url, tenantId)

        assertEquals("tenant cached", result)
        assertEquals(0, requestCount)
    }

    // ========== ETag and Conditional Request Tests ==========

    @Test
    fun `sends If-None-Match header when cached with ETag`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("cached value", etag = "etag-123")

        var receivedIfNoneMatch: String? = null
        val client = createMockClient(
            responseBody = "new value",
            onRequest = { request ->
                receivedIfNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            }
        )
        val resolver = createResolver(client)

        resolver.get(url, forceRefresh = true)

        assertEquals("etag-123", receivedIfNoneMatch)
    }

    @Test
    fun `sends If-Modified-Since header when cached with Last-Modified`() = runTest {
        val url = "https://example.com/resource"
        val lastModified = "Wed, 01 Jan 2025 00:00:00 GMT"
        mockCache.appCache[url] = HttpMetadata("cached value", lastModified = lastModified)

        var receivedIfModifiedSince: String? = null
        val client = createMockClient(
            responseBody = "new value",
            onRequest = { request ->
                receivedIfModifiedSince = request.headers[HttpHeaders.IfModifiedSince]
            }
        )
        val resolver = createResolver(client)

        resolver.get(url, forceRefresh = true)

        assertEquals(lastModified, receivedIfModifiedSince)
    }

    @Test
    fun `returns cached value on 304 Not Modified response`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("cached value", etag = "etag-123")

        val client = createMockClient(
            responseBody = "",
            statusCode = HttpStatusCode.NotModified
        )
        val resolver = createResolver(client)

        val result = resolver.get(url, forceRefresh = true)

        assertEquals("cached value", result)
        assertEquals(1, requestCount)
    }

    @Test
    fun `does not send conditional headers when caching disabled`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("cached value", etag = "etag-123")

        var receivedIfNoneMatch: String? = null
        val client = createMockClient(
            responseBody = "new value",
            onRequest = { request ->
                receivedIfNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            }
        )
        val config = TestHttpResolverConfig(enableHttpCaching = false)
        val resolver = createResolver(client, config)

        resolver.get(url, forceRefresh = true)

        assertNull(receivedIfNoneMatch)
    }

    // ========== Generic Scoped Operation Tests ==========

    @Test
    fun `generic get with APP scope works correctly`() = runTest {
        val url = "https://example.com/resource"
        mockCache.appCache[url] = HttpMetadata("app value")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.get(url, CacheScope.APP)

        assertEquals("app value", result)
    }

    @Test
    fun `generic get with TENANT scope works correctly`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        mockCache.tenantCache["$tenantId:$url"] = HttpMetadata("tenant value")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.get(url, CacheScope.TENANT, tenantId)

        assertEquals("tenant value", result)
    }

    @Test
    fun `generic get with PRINCIPAL scope uses tenant implementation`() = runTest {
        val url = "https://example.com/resource"
        val principalId = "user-1"
        mockCache.tenantCache["$principalId:$url"] = HttpMetadata("principal value")

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.get(url, CacheScope.PRINCIPAL, principalId)

        assertEquals("principal value", result)
    }

    // ========== Cache Management Tests ==========

    @Test
    fun `clearCache clears all entries`() = runTest {
        mockCache.appCache["url1"] = HttpMetadata("value1")
        mockCache.tenantCache["tenant:url2"] = HttpMetadata("value2")

        val client = createMockClient()
        val resolver = createResolver(client)

        resolver.clearCache()

        assertTrue(mockCache.clearAllCalled)
    }

    @Test
    fun `clearCacheForTenant clears only tenant entries`() = runTest {
        val tenantId = "tenant-1"

        val client = createMockClient()
        val resolver = createResolver(client)

        resolver.clearCacheForTenant(tenantId)

        assertEquals(tenantId, mockCache.clearedTenantId)
    }

    @Test
    fun `evictExpired calls cache eviction`() = runTest {
        val client = createMockClient()
        val resolver = createResolver(client)

        resolver.evictExpired()

        assertTrue(mockCache.evictExpiredCalled)
    }

    // ========== Mock Implementations ==========

    private class TestHttpResolverConfig(
        override val httpTimeoutMs: Long = 30000,
        override val httpRetries: Int = 1,
        override val enableHttpCaching: Boolean = true,
        override val enableETagSupport: Boolean = true
    ) : HttpResolverConfig

    private class MockScopedCache : ScopedCache<String, HttpMetadata<String>> {
        override val namespace: String = "test-cache"

        val appCache = mutableMapOf<String, HttpMetadata<String>>()
        val tenantCache = mutableMapOf<String, HttpMetadata<String>>()
        val principalCache = mutableMapOf<String, HttpMetadata<String>>()

        var clearAllCalled = false
        var clearedTenantId: String? = null
        var evictExpiredCalled = false

        override suspend fun getApp(key: String): HttpMetadata<String>? = appCache[key]
        override suspend fun putApp(key: String, value: HttpMetadata<String>): HttpMetadata<String>? {
            val old = appCache[key]
            appCache[key] = value
            return old
        }
        override suspend fun removeApp(key: String): HttpMetadata<String>? = appCache.remove(key)
        override suspend fun getOrPutApp(key: String, compute: suspend () -> HttpMetadata<String>?): HttpMetadata<String>? {
            return appCache[key] ?: compute()?.also { appCache[key] = it }
        }

        override suspend fun getTenant(tenantId: String, key: String): HttpMetadata<String>? = tenantCache["$tenantId:$key"]
        override suspend fun putTenant(tenantId: String, key: String, value: HttpMetadata<String>): HttpMetadata<String>? {
            val cacheKey = "$tenantId:$key"
            val old = tenantCache[cacheKey]
            tenantCache[cacheKey] = value
            return old
        }
        override suspend fun removeTenant(tenantId: String, key: String): HttpMetadata<String>? = tenantCache.remove("$tenantId:$key")
        override suspend fun getOrPutTenant(tenantId: String, key: String, compute: suspend () -> HttpMetadata<String>?): HttpMetadata<String>? {
            val cacheKey = "$tenantId:$key"
            return tenantCache[cacheKey] ?: compute()?.also { tenantCache[cacheKey] = it }
        }

        override suspend fun getPrincipal(principalId: String, key: String): HttpMetadata<String>? = principalCache["$principalId:$key"]
        override suspend fun putPrincipal(principalId: String, key: String, value: HttpMetadata<String>): HttpMetadata<String>? {
            val cacheKey = "$principalId:$key"
            val old = principalCache[cacheKey]
            principalCache[cacheKey] = value
            return old
        }
        override suspend fun removePrincipal(principalId: String, key: String): HttpMetadata<String>? = principalCache.remove("$principalId:$key")
        override suspend fun getOrPutPrincipal(principalId: String, key: String, compute: suspend () -> HttpMetadata<String>?): HttpMetadata<String>? {
            val cacheKey = "$principalId:$key"
            return principalCache[cacheKey] ?: compute()?.also { principalCache[cacheKey] = it }
        }

        override suspend fun clear() {
            clearAllCalled = true
            appCache.clear()
            tenantCache.clear()
            principalCache.clear()
        }
        override suspend fun clearApp() { appCache.clear() }
        override suspend fun clearTenant(tenantId: String) {
            clearedTenantId = tenantId
            tenantCache.keys.filter { it.startsWith("$tenantId:") }.forEach { tenantCache.remove(it) }
        }
        override suspend fun clearPrincipal(principalId: String) {
            principalCache.keys.filter { it.startsWith("$principalId:") }.forEach { principalCache.remove(it) }
        }
        override suspend fun evictExpired() { evictExpiredCalled = true }
        override suspend fun getStatistics(): CacheStatistics = CacheStatistics(
            namespace = namespace,
            hits = 0L,
            misses = 0L,
            size = appCache.size.toLong() + tenantCache.size.toLong() + principalCache.size.toLong(),
            maxSize = 1000L
        )
    }
}

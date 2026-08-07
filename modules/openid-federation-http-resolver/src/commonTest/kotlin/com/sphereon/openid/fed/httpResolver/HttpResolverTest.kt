package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheScope
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.config.HttpResolverConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for HttpResolver using real IDK [ScopedCache] (via [OidfCache]).
 */
class HttpResolverTest {

    private lateinit var cache: ScopedCache<String, HttpMetadata<String>>
    private var requestCount: Int = 0
    private var lastRequestHeaders: Headers? = null

    @BeforeTest
    fun setup() {
        val manager = OidfCache.newManager()
        cache = OidfCache.createStringKeyCache(
            manager = manager,
            requirements = CacheRequirements(namespace = "http-resolver-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        requestCount = 0
        lastRequestHeaders = null
    }

    private fun createMockClient(
        responseBody: String = "test response",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        responseHeaders: Map<String, String> = emptyMap(),
        onRequest: ((io.ktor.client.request.HttpRequestData) -> Unit)? = null
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
                        headers = headersOf(*responseHeaders.map { (k, v) -> k to listOf(v) }.toTypedArray())
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
            cache = cache,
            responseMapper = { response -> response.bodyAsText() }
        )
    }

    // ========== APP Scope Tests ==========

    @Test
    fun `get with APP scope returns cached value on cache hit`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("cached value", etag = "etag-123"))

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
        val cached = cache.getApp(url)
        assertNotNull(cached, "Should cache the response")
        assertEquals("etag-456", cached.etag)
    }

    @Test
    fun `get with forceRefresh bypasses cache`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("old cached value", etag = "old-etag"))

        val client = createMockClient(
            responseBody = "new value",
            responseHeaders = mapOf(HttpHeaders.ETag to "new-etag")
        )
        val resolver = createResolver(client)

        val result = resolver.get(url, forceRefresh = true)

        assertEquals("new value", result)
        assertEquals(1, requestCount, "Should make HTTP request even with cache")
        assertEquals("new-etag", cache.getApp(url)?.etag, "Should update cache")
    }

    @Test
    fun `getCachedApp returns cached value without fetching`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("cached only"))

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
    fun `getForTenant returns cached value on hit`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        cache.putTenant(tenantId, url, HttpMetadata("tenant cached value"))

        val client = createMockClient()
        val resolver = createResolver(client)

        val result = resolver.getForTenant(url, tenantId)

        assertEquals("tenant cached value", result)
        assertEquals(0, requestCount)
    }

    @Test
    fun `getForTenant isolates tenants`() = runTest {
        val url = "https://example.com/resource"
        cache.putTenant("tenant-1", url, HttpMetadata("tenant-1 value"))
        cache.putTenant("tenant-2", url, HttpMetadata("tenant-2 value"))

        val client = createMockClient()
        val resolver = createResolver(client)

        assertEquals("tenant-1 value", resolver.getForTenant(url, "tenant-1"))
        assertEquals("tenant-2 value", resolver.getForTenant(url, "tenant-2"))
        assertEquals(0, requestCount)
    }

    @Test
    fun `getForTenant fetches on miss`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        val client = createMockClient(responseBody = "remote tenant")
        val resolver = createResolver(client)

        val result = resolver.getForTenant(url, tenantId)

        assertEquals("remote tenant", result)
        assertEquals(1, requestCount)
        assertNotNull(cache.getTenant(tenantId, url))
    }

    @Test
    fun `getCachedTenant returns only tenant value`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "tenant-1"
        cache.putTenant(tenantId, url, HttpMetadata("tenant cached"))

        val client = createMockClient()
        val resolver = createResolver(client)

        assertEquals("tenant cached", resolver.getCachedTenant(url, tenantId))
        assertNull(resolver.getCachedTenant(url, "other-tenant"))
        assertEquals(0, requestCount)
    }

    // ========== Conditional headers ==========

    @Test
    fun `sends If-None-Match when etag cached and force refresh`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("cached value", etag = "etag-123"))

        val client = createMockClient(responseBody = "fresh")
        val resolver = createResolver(client)

        resolver.get(url, forceRefresh = true)

        assertEquals("etag-123", lastRequestHeaders?.get(HttpHeaders.IfNoneMatch))
    }

    @Test
    fun `sends If-Modified-Since when lastModified cached and force refresh`() = runTest {
        val url = "https://example.com/resource"
        val lastModified = "Wed, 01 Jan 2025 00:00:00 GMT"
        cache.putApp(url, HttpMetadata("cached value", lastModified = lastModified))

        val client = createMockClient(responseBody = "fresh")
        val resolver = createResolver(client)

        resolver.get(url, forceRefresh = true)

        assertEquals(lastModified, lastRequestHeaders?.get(HttpHeaders.IfModifiedSince))
    }

    @Test
    fun `304 Not Modified returns cached body`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("cached value", etag = "etag-123"))

        val client = createMockClient(statusCode = HttpStatusCode.NotModified)
        val resolver = createResolver(client)

        val result = resolver.get(url, forceRefresh = true)

        assertEquals("cached value", result)
        assertEquals(1, requestCount)
    }

    // ========== Generic scope API ==========

    @Test
    fun `get with CacheScope APP works`() = runTest {
        val url = "https://example.com/resource"
        cache.putApp(url, HttpMetadata("app value"))

        val client = createMockClient()
        val resolver = createResolver(client)

        assertEquals("app value", resolver.get(url, CacheScope.APP))
    }

    @Test
    fun `get with CacheScope TENANT works`() = runTest {
        val url = "https://example.com/resource"
        val tenantId = "t1"
        cache.putTenant(tenantId, url, HttpMetadata("tenant value"))

        val client = createMockClient()
        val resolver = createResolver(client)

        assertEquals("tenant value", resolver.get(url, CacheScope.TENANT, tenantId))
    }

    @Test
    fun `get with CacheScope PRINCIPAL uses tenant path`() = runTest {
        val url = "https://example.com/resource"
        val principalId = "p1"
        cache.putTenant(principalId, url, HttpMetadata("principal value"))

        val client = createMockClient()
        val resolver = createResolver(client)

        assertEquals("principal value", resolver.get(url, CacheScope.PRINCIPAL, principalId))
    }

    // ========== Cache management ==========

    @Test
    fun `clearCache clears all entries`() = runTest {
        cache.putApp("url1", HttpMetadata("value1"))
        cache.putTenant("tenant", "url2", HttpMetadata("value2"))

        val client = createMockClient()
        val resolver = createResolver(client)

        resolver.clearCache()

        assertNull(cache.getApp("url1"))
        assertNull(cache.getTenant("tenant", "url2"))
    }

    @Test
    fun `clearCacheForTenant invalidates tenant entries`() = runTest {
        val tenantId = "tenant-1"
        cache.putTenant(tenantId, "url", HttpMetadata("v"))
        cache.putApp("shared", HttpMetadata("app"))

        val client = createMockClient()
        val resolver = createResolver(client)

        resolver.clearCacheForTenant(tenantId)

        assertNull(cache.getTenant(tenantId, "url"))
        // app scope should remain
        assertEquals("app", cache.getApp("shared")?.value)
    }

    private class TestHttpResolverConfig(
        override val httpTimeoutMs: Long = 30000,
        override val httpRetries: Int = 1,
        override val enableHttpCaching: Boolean = true,
        override val enableEtagSupport: Boolean = true
    ) : HttpResolverConfig
}

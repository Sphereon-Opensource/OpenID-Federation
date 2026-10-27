package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.openid.fed.httpResolver.config.HttpResolverDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cached federation statements are bounded by their signed `exp`, the HTTP freshness lifetime and the reuse limit. */
@OptIn(ExperimentalEncodingApi::class)
class HttpResolverStatementExpiryTest {
    private val url = "https://entity.example/.well-known/openid-federation"
    private val type = "application/entity-statement+jwt"
    private val start = 1_800_000_000L
    private var now = start
    private var requests = 0
    private val bodies = ArrayDeque<String>()
    private var headers: Map<String, String> = emptyMap()
    private lateinit var cache: ScopedCache<String, HttpMetadata<String>>
    private lateinit var client: HttpClient

    @BeforeTest
    fun setUp() {
        cache = OidfCache.createStringKeyCache(
            OidfCache.newManager(),
            CacheRequirements(namespace = "http-resolver-expiry-test"),
            HttpMetadataCacheSerializers.stringValue,
        )
        client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requests++
                    val all = headers + (HttpHeaders.ContentType to type)
                    respond(bodies.removeFirst(), HttpStatusCode.OK,
                        headersOf(*all.map { (k, v) -> k to listOf(v) }.toTypedArray()))
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private fun resolver(config: DefaultHttpResolverConfig = DefaultHttpResolverConfig()) =
        HttpResolver(config = config, httpClient = client, cache = cache, responseMapper = { it.bodyAsText() })
            .also { it.epochSeconds = { now } }

    private fun jwt(exp: Any?, marker: String): String {
        fun b64(s: String) = Base64.UrlSafe.encode(s.encodeToByteArray()).trimEnd('=')
        val claims = buildString {
            append("{\"iss\":\"https://entity.example\",\"marker\":\"").append(marker).append('"')
            if (exp != null) append(",\"exp\":").append(exp)
            append('}')
        }
        return b64("{\"alg\":\"ES256\",\"kid\":\"k\"}") + "." + b64(claims) + ".c2ln"
    }

    @Test
    fun cachedStatementIsNeverServedAtOrPastItsExp() = runTest {
        val first = jwt(start + 60, "first")
        val renewed = jwt(start + 3660, "renewed")
        bodies += listOf(first, renewed)
        val resolver = resolver()
        assertEquals(first, resolver.getJwt(url))
        now = start + 59
        assertEquals(first, resolver.getJwt(url), "Still fresh: served from cache")
        assertEquals(1, requests)
        now = start + 60
        assertEquals(renewed, resolver.getJwt(url), "At exp the cached statement must be refetched")
        assertEquals(2, requests)
    }

    @Test
    fun cacheControlMaxAgeShortensTheCacheLifetimeBelowExp() = runTest {
        headers = mapOf(HttpHeaders.CacheControl to "public, max-age=30", HttpHeaders.Age to "10")
        val first = jwt(start + 3600, "first")
        val second = jwt(start + 3600, "second")
        bodies += listOf(first, second)
        val resolver = resolver()
        assertEquals(first, resolver.getJwt(url))
        now = start + 19
        assertEquals(first, resolver.getJwt(url))
        now = start + 20
        assertEquals(second, resolver.getJwt(url), "max-age less Age bounds the cache lifetime")
        assertEquals(2, requests)
    }

    @Test
    fun noStoreNoCacheExpiredAndExpLessStatementsAreNotCached() = runTest {
        val cases = listOf(
            mapOf(HttpHeaders.CacheControl to "no-store") to jwt(start + 3600, "no-store"),
            mapOf(HttpHeaders.CacheControl to "no-cache") to jwt(start + 3600, "no-cache"),
            emptyMap<String, String>() to jwt(start - 1, "expired"),
            emptyMap<String, String>() to jwt(null, "no-exp"),
            emptyMap<String, String>() to jwt("\"${start + 3600}\"", "string-exp"),
        )
        for ((caseHeaders, body) in cases) {
            headers = caseHeaders
            bodies += listOf(body, body)
            val before = requests
            val resolver = resolver()
            assertEquals(body, resolver.getJwt(url))
            assertEquals(body, resolver.getJwt(url))
            assertEquals(before + 2, requests, "Not cacheable: $caseHeaders $body")
        }
    }

    @Test
    fun fractionalExpIsFlooredAndAnEntryWithoutAFreshnessBoundIsNotServed() = runTest {
        cache.putApp(url, HttpMetadata(jwt(start + 3600, "legacy"), jwtContentType = type))
        val fractional = jwt("${start + 30}.9", "fractional")
        val next = jwt(start + 3600, "next")
        bodies += listOf(fractional, next)
        val resolver = resolver()
        assertEquals(fractional, resolver.getJwt(url), "A typed entry without a freshness bound is refetched")
        now = start + 30
        assertEquals(next, resolver.getJwt(url), "exp 30.9 expires at second 30")
        assertEquals(2, requests)
    }

    @Test
    fun bypassReplacesTheSharedEntryWithTheFreshStatement() = runTest {
        val stale = jwt(start + 3600, "stale")
        val fresh = jwt(start + 3600, "fresh")
        bodies += listOf(stale, fresh)
        val resolver = resolver()
        assertEquals(stale, resolver.getJwt(url))
        assertEquals(fresh, resolver.getJwt(url, bypassCache = true))
        assertEquals(fresh, resolver.getJwt(url), "Later cached reads see the refetched statement")
        assertEquals(2, requests)
    }

    @Test
    fun invalidateRemovesTheCachedStatement() = runTest {
        val first = jwt(start + 3600, "first")
        val republished = jwt(start + 3600, "republished")
        bodies += listOf(first, republished)
        val resolver = resolver()
        assertEquals(first, resolver.getJwt(url))
        resolver.invalidate(url)
        assertNull(resolver.getCachedApp(url))
        assertEquals(republished, resolver.getJwt(url))
        assertEquals(2, requests)
    }

    @Test
    fun aLongLivedStatementIsRefetchedAfterTheDefaultReuseLimit() = runTest {
        val limit = HttpResolverDefaults.DEFAULT_MAX_STATEMENT_REUSE_SECONDS
        val original = jwt(start + 86_400, "original")
        val resigned = jwt(start + 86_400, "resigned")
        bodies += listOf(original, resigned)
        val resolver = resolver()
        assertEquals(original, resolver.getJwt(url))
        now = start + limit - 1
        assertEquals(original, resolver.getJwt(url), "Within the reuse limit: served from cache")
        assertEquals(1, requests)
        now = start + limit
        assertEquals(resigned, resolver.getJwt(url), "A superior's re-signed statement is seen once the reuse limit passes")
        assertEquals(2, requests)
    }

    @Test
    fun aConfiguredReuseLimitAppliesAndNullKeepsOnlyExpAndHttpFreshness() = runTest {
        val first = jwt(start + 86_400, "first")
        val second = jwt(start + 86_400, "second")
        bodies += listOf(first, second)
        val short = resolver(DefaultHttpResolverConfig(maxStatementReuseSeconds = 10))
        assertEquals(first, short.getJwt(url))
        now = start + 10
        assertEquals(second, short.getJwt(url))
        assertEquals(2, requests)

        val third = jwt(start + 86_400, "third")
        bodies += listOf(third)
        val unlimited = resolver(DefaultHttpResolverConfig(maxStatementReuseSeconds = null))
        assertEquals(third, unlimited.getJwt(url, bypassCache = true))
        now = start + 10 + 3_600
        assertEquals(third, unlimited.getJwt(url), "Without a reuse limit only exp and Cache-Control bound the cache")
        assertEquals(3, requests)
    }
}

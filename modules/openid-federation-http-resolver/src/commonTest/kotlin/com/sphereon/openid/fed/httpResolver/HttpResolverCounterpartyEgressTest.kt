package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.ktor.http.client.provider.UrlValidationPolicy
import com.sphereon.ktor.http.client.provider.isEgressRefusal
import com.sphereon.ktor.http.client.provider.withCounterpartyEgress
import com.sphereon.openid.fed.core.cache.OidfCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A call marked as a holder counterparty fetch uses the counterparty egress route; other calls keep the server route. */
class HttpResolverCounterpartyEgressTest {
    private lateinit var cache: ScopedCache<String, HttpMetadata<String>>
    private var serverRequests = 0
    private var holderRequests = 0
    private var holderClientsCreated = 0

    @BeforeTest
    fun setup() {
        cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "http-resolver-counterparty-egress-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        serverRequests = 0
        holderRequests = 0
        holderClientsCreated = 0
    }

    private fun client(body: String, onRequest: () -> Unit): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                onRequest()
                respond(content = body, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/plain"))
            }
        }
    }

    /** The server route admits every URL, standing in for a server policy that is looser than the holder rule. */
    private fun resolver(): HttpResolver<String> =
        HttpResolver(
            httpClient = client("server") { serverRequests++ },
            cache = cache,
            responseMapper = { it.bodyAsText() },
        ).also { resolver ->
            resolver.installRequestPolicy("server-policy") { }
            resolver.installCounterpartyEgressRoute(
                cacheNamespace = "holder-policy",
                httpClient = {
                    holderClientsCreated++
                    client("holder") { holderRequests++ }
                },
            ) { url -> UrlValidationPolicy.COUNTERPARTY_EGRESS.validate(Url(url)) }
        }

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
        } catch (refused: Exception) {
            assertTrue(refused.isEgressRefusal(), "Expected an egress refusal, got $refused")
            return
        }
        fail("Expected the fetch to be refused")
    }

    @Test
    fun markedFetchOfLoopbackOrPrivateTargetIsRefusedWithoutRequest() = runTest {
        val resolver = resolver()
        for (url in listOf(
            "https://127.0.0.1/.well-known/openid-federation",
            "https://10.0.0.5/.well-known/openid-federation",
            "https://169.254.169.254/latest/meta-data",
            "https://localhost/.well-known/openid-federation",
        )) {
            withCounterpartyEgress {
                assertRefused { resolver.get(url) }
                assertRefused { resolver.getForTenant(url, "tenant-a") }
                assertRefused { resolver.getJwt(url, bypassCache = true) }
                assertRefused { resolver.getTypedJwt(url, "application/trust-mark+jwt") }
                assertRefused { resolver.postJwt(url, Parameters.build { append("trust_mark", "x") }) }
            }
        }
        assertEquals(0, serverRequests)
        assertEquals(0, holderRequests)
        assertEquals(0, holderClientsCreated, "A refused fetch must not even create the holder client")
    }

    @Test
    fun unmarkedFetchKeepsTheServerPolicyAndClient() = runTest {
        val resolver = resolver()
        assertEquals("server", resolver.get("https://10.0.0.5/.well-known/openid-federation"))
        assertEquals(1, serverRequests)
        assertEquals(0, holderRequests)
        assertEquals(0, holderClientsCreated)
    }

    @Test
    fun markedFetchUsesTheHolderClientAndNeverReadsServerCacheEntries() = runTest {
        val resolver = resolver()
        val url = "https://federation.example.com/.well-known/openid-federation"
        assertEquals("server", resolver.get(url))
        assertEquals("server", resolver.get(url))
        assertEquals(1, serverRequests)

        assertEquals("holder", withCounterpartyEgress { resolver.get(url) })
        assertEquals("holder", withCounterpartyEgress { resolver.get(url) })
        assertEquals(1, holderRequests, "The holder entry is cached in its own partition")
        assertEquals(1, holderClientsCreated)

        assertEquals("server", resolver.get(url), "A holder entry is never served to a server call")
        assertEquals(1, serverRequests)
    }

    @Test
    fun withoutAnInstalledRouteMarkedCallsKeepTheRequestPolicy() = runTest {
        val resolver = HttpResolver(
            httpClient = client("server") { serverRequests++ },
            cache = cache,
            responseMapper = { it.bodyAsText() },
        ).also { it.installRequestPolicy("server-policy") { } }
        assertEquals("server", withCounterpartyEgress { resolver.get("https://federation.example.com/x") })
        assertEquals(1, serverRequests)
    }
}

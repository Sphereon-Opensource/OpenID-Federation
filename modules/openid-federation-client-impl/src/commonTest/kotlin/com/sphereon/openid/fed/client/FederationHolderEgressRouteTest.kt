package com.sphereon.openid.fed.client

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.ktor.http.client.provider.UrlValidationPolicy
import com.sphereon.ktor.http.client.provider.isEgressRefusal
import com.sphereon.ktor.http.client.provider.withCounterpartyEgress
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.httpResolver.config.DefaultHttpResolverConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Federation fetches made inside a wallet holder's counterparty call (entity configurations, subordinate statements,
 * trust chains, trust marks, JWKS) run under the plain counterparty egress rule, never under the operator's private
 * IPv4 targets, and never read entries the server route cached.
 */
class FederationHolderEgressRouteTest {
    private var serverRequests = 0
    private var holderRequests = 0
    private var holderClientsCreated = 0

    private fun client(body: String, onRequest: () -> Unit): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                onRequest()
                respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }

    private fun resolver(serverPolicy: UrlValidationPolicy): HttpResolver<String> = federationHttpResolver(
        config = DefaultHttpResolverConfig(),
        httpClient = client("server") { serverRequests++ },
        cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "federation-holder-egress-route-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        ),
        egressPolicy = serverPolicy,
        cacheNamespace = federationCounterpartyCacheNamespace(serverPolicy),
        holderHttpClient = {
            holderClientsCreated++
            client("holder") { holderRequests++ }
        },
    )

    private suspend fun assertRefused(url: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (refused: Exception) {
            assertTrue(refused.isEgressRefusal(), "Expected an egress refusal for $url, got $refused")
            return
        }
        fail("Expected $url to be refused")
    }

    @Test
    fun holderFetchOfLoopbackOrPrivateTargetIsRefusedWithoutNetworkCall() = runTest {
        val resolver = resolver(federationCounterpartyUrlValidationPolicy("sphereon.test:443=10.0.0.5"))
        for (url in listOf(
            "https://127.0.0.1/.well-known/openid-federation",
            "https://10.0.0.5/.well-known/openid-federation",
            "https://10.0.0.5/fetch?sub=https%3A%2F%2Fleaf.example.com",
            "https://localhost/.well-known/openid-federation",
        )) {
            withCounterpartyEgress {
                assertRefused(url) { resolver.get(url) }
                assertRefused(url) { resolver.getJwt(url, bypassCache = true) }
            }
        }
        assertEquals(0, serverRequests)
        assertEquals(0, holderRequests)
        assertEquals(0, holderClientsCreated)
    }

    @Test
    fun holderFetchUsesAClientWithoutTheOperatorPrivateTargets() = runTest {
        val resolver = resolver(federationCounterpartyUrlValidationPolicy("sphereon.test:443=10.0.0.5"))
        val url = "https://sphereon.test/.well-known/openid-federation"

        assertEquals("server", resolver.get(url))
        assertEquals("holder", withCounterpartyEgress { resolver.get(url) }, "A holder never reads a server cache entry")
        assertEquals(1, serverRequests)
        assertEquals(1, holderRequests)
        assertEquals(1, holderClientsCreated)
        assertEquals(UrlValidationPolicy.COUNTERPARTY_EGRESS, HOLDER_COUNTERPARTY_EGRESS_POLICY)
        assertTrue(HOLDER_COUNTERPARTY_EGRESS_POLICY.resolvedPrivateIpv4Targets.isEmpty())
    }

    @Test
    fun withoutPrivateTargetsTheServerClientIsSharedButCachesStaySeparate() = runTest {
        val resolver = resolver(federationCounterpartyUrlValidationPolicy())
        val url = "https://federation.example.com/.well-known/openid-federation"

        assertEquals("server", resolver.get(url))
        assertEquals("server", withCounterpartyEgress { resolver.get(url) })
        assertEquals(2, serverRequests, "The holder call fetches again in its own cache partition")
        assertEquals(0, holderClientsCreated)
        assertNotEquals(federationCounterpartyCacheNamespace(UrlValidationPolicy.COUNTERPARTY_EGRESS), HOLDER_COUNTERPARTY_EGRESS_CACHE_NAMESPACE)
    }

    @Test
    fun serverCallsKeepTheirConfiguredPolicy() = runTest {
        val resolver = resolver(federationCounterpartyUrlValidationPolicy())
        val url = "https://127.0.0.1/.well-known/openid-federation"
        try {
            resolver.get(url)
            fail("The server federation policy also refuses loopback")
        } catch (refused: Exception) {
            assertTrue(refused.isEgressRefusal())
        }
        assertEquals(0, serverRequests)
    }
}

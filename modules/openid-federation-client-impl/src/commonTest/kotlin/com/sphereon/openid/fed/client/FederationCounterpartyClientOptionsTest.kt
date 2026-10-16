package com.sphereon.openid.fed.client

import com.sphereon.ktor.http.client.provider.UrlValidationPolicy
import com.sphereon.ktor.http.client.provider.ResolvedPrivateIpv4Target
import com.sphereon.ktor.http.client.provider.HttpClientOptions
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadata
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FederationCounterpartyClientOptionsTest {
    @Test
    fun productionFactoryOptionsProtectEveryRequestWithoutCredentialDefaults() {
        val options = federationCounterpartyClientOptions()
        assertEquals(UrlValidationPolicy.COUNTERPARTY_EGRESS, options.urlValidation)
        assertFalse(options.followRedirects)
        assertNull(options.defaultRequest)
        assertNull(options.additionalConfig)
        assertEquals(HttpClientOptions.createDefault().sslConfig, options.sslConfig)
    }

    @Test
    fun privateTargetConfigurationIsExactAndDoesNotChangeTlsOrRedirectPolicy() {
        val options = federationCounterpartyClientOptions("sphereon.test:443=10.253.140.12|10.253.140.13")
        val expectedTarget = ResolvedPrivateIpv4Target("sphereon.test", 443, setOf("10.253.140.12", "10.253.140.13"))
        assertEquals(
            UrlValidationPolicy.COUNTERPARTY_EGRESS.copy(resolvedPrivateIpv4Targets = setOf(expectedTarget)),
            options.urlValidation,
        )
        val configuredPolicy = checkNotNull(options.urlValidation)
        assertNotEquals(
            federationCounterpartyCacheNamespace(UrlValidationPolicy.COUNTERPARTY_EGRESS),
            federationCounterpartyCacheNamespace(configuredPolicy),
        )
        val reorderedPolicy = federationCounterpartyUrlValidationPolicy("sphereon.test:443=10.253.140.13|10.253.140.12")
        assertEquals(
            federationCounterpartyCacheNamespace(configuredPolicy),
            federationCounterpartyCacheNamespace(reorderedPolicy),
            "Address declaration order must not change the cache policy identity",
        )
        assertFalse(options.followRedirects)
        assertEquals(HttpClientOptions.createDefault().sslConfig, options.sslConfig)
    }

    @Test
    fun malformedOrOverBroadPrivateTargetConfigurationFailsClosed() {
        listOf(
            "*.sphereon.test:443=10.1.2.3",
            "https://sphereon.test:443=10.1.2.3",
            "user@sphereon.test:443=10.1.2.3",
            "sphereon.test/path:443=10.1.2.3",
            "sphereon.test:443#fragment=10.1.2.3",
            "localhost:443=10.1.2.3",
            "api.localhost:443=10.1.2.3",
            "api.local:443=10.1.2.3",
            "api.internal:443=10.1.2.3",
            "metadata.google.internal:443=10.1.2.3",
            "127.0.0.1:443=10.1.2.3",
            "[::1]:443=10.1.2.3",
            "192.168.1.2:443=10.1.2.3",
            "sphereon.test:0443=10.1.2.3",
            "sphereon.test:65536=10.1.2.3",
            "sphereon.test:443=127.0.0.1",
            "sphereon.test:443=169.254.1.2",
            "sphereon.test:443=0.0.0.0",
            "sphereon.test:443=224.0.0.1",
            "sphereon.test:443=100.64.0.1",
            "sphereon.test:443=010.1.2.3",
            "sphereon.test:443=10.1.2.3;sphereon.test:444=192.168.1.1",
        ).forEach { config ->
            assertFailsWith<IllegalArgumentException>(config) { parseFederationPrivateIpv4Targets(config) }
        }
    }

    @Test
    fun removedPrivateTargetDoesNotReuseSharedAppOrTenantCacheEntry() = runTest {
        val url = "https://sphereon.test/.well-known/openid-federation"
        val cache: ScopedCache<String, HttpMetadata<String>> = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "counterparty-egress-policy-partition-test"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        var requestCount = 0

        fun resolver(policy: UrlValidationPolicy, responseBody: String): HttpResolver<String> {
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        requestCount++
                        respond(content = responseBody, status = HttpStatusCode.OK)
                    }
                }
            }
            return HttpResolver(
                httpClient = client,
                cache = cache,
                responseMapper = { response -> response.bodyAsText() },
            ).also { httpResolver ->
                httpResolver.installRequestPolicy(federationCounterpartyCacheNamespace(policy)) { requested ->
                    policy.validate(Url(requested))
                }
            }
        }

        val previouslyApproved = resolver(
            federationCounterpartyUrlValidationPolicy("sphereon.test:443=10.253.140.12"),
            "previously approved response",
        )
        assertEquals("previously approved response", previouslyApproved.get(url))
        assertEquals("previously approved response", previouslyApproved.getForTenant(url, "tenant-a"))

        val currentDefault = resolver(
            federationCounterpartyUrlValidationPolicy(),
            "current-policy fetch",
        )
        assertEquals("current-policy fetch", currentDefault.get(url))
        assertEquals("current-policy fetch", currentDefault.getForTenant(url, "tenant-a"))
        assertEquals(4, requestCount, "The new policy must not read the prior policy's APP or TENANT cache entries")
    }
}

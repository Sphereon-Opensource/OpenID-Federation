package com.sphereon.openid.fed.services.command.renewal

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Publishing in this process drops the co-located resolver's cached copy, so its next read sees the new statement. */
class FederationContextStatementCacheInvalidatorTest {
    private val issuer = "https://superior.example"
    private val subject = "https://subordinate.example"
    private val ecUrl = "$issuer/.well-known/openid-federation"
    private val ssUrl = "$issuer/federation/fetch?sub=$subject"
    private val defaultSsUrl = "$issuer/fetch?sub=$subject"
    private val served = mutableMapOf<String, String>()
    private var requests = 0
    private lateinit var client: HttpClient
    private lateinit var resolver: HttpResolver<String>

    @BeforeTest
    fun setUp() {
        client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests++
                    val url = request.url
                    val key = "${url.protocol.name}://${url.host}${url.encodedPath}" +
                        (url.parameters["sub"]?.let { "?sub=$it" } ?: "")
                    respond(served.getValue(key), HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt"))
                }
            }
        }
        resolver = HttpResolver(httpClient = client, responseMapper = { it.bodyAsText() },
            cache = OidfCache.createStringKeyCache(OidfCache.newManager(),
                CacheRequirements(namespace = "invalidator-${hashCode()}"), HttpMetadataCacheSerializers.stringValue))
    }

    @AfterTest
    fun tearDown() = client.close()

    private fun jwt(iss: String, sub: String, marker: String, sourceEndpoint: String? = null): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val now = System.currentTimeMillis() / 1000
        val source = sourceEndpoint?.let { ",\"source_endpoint\":\"$it\"" } ?: ""
        return b64("{\"alg\":\"ES256\",\"kid\":\"k1\",\"typ\":\"entity-statement+jwt\"}") + "." +
            b64("{\"iss\":\"$iss\",\"sub\":\"$sub\",\"iat\":$now,\"exp\":${now + 3600},\"jti\":\"$marker\"$source}") + ".c2ln"
    }

    private fun invalidator() = FederationContextStatementCacheInvalidator(lazyOf(FederationContext(mockk<JwtService>(), resolver)))

    @Test
    fun republishedEntityConfigurationIsReadFreshByTheLocalResolver() = runTest {
        served[ecUrl] = jwt(issuer, issuer, "old")
        resolver.getJwt(ecUrl)
        val republished = jwt(issuer, issuer, "new")
        served[ecUrl] = republished

        invalidator().published(republished)

        assertNull(resolver.getCachedApp(ecUrl))
        assertEquals(republished, resolver.getJwt(ecUrl))
        assertEquals(2, requests)
    }

    @Test
    fun republishedSubordinateStatementIsInvalidatedAtItsSourceAndDefaultFetchUrls() = runTest {
        served[ssUrl] = jwt(issuer, subject, "old", "$issuer/federation/fetch")
        served[defaultSsUrl] = served.getValue(ssUrl)
        resolver.getJwt(ssUrl)
        resolver.getJwt(defaultSsUrl)
        assertNotNull(resolver.getCachedApp(ssUrl))

        invalidator().published(jwt(issuer, subject, "new", "$issuer/federation/fetch"))

        assertNull(resolver.getCachedApp(ssUrl))
        assertNull(resolver.getCachedApp(defaultSsUrl))
        assertEquals(
            listOf(ssUrl, defaultSsUrl),
            FederationContextStatementCacheInvalidator.cachedUrls(jwt(issuer, subject, "x", "$issuer/federation/fetch?ignored=1")),
        )
    }

    @Test
    fun anUndecodableStatementNeverFailsThePublish() = runTest {
        invalidator().published("not-a-jwt")
    }
}

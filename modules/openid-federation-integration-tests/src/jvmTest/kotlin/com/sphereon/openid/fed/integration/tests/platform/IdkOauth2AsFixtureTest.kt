package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.openid.fed.core.tenant.BearerTokenSupport
import com.sphereon.openid.fed.core.tenant.PlatformJwtTenantClaims
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Always-on smoke: real IDK OAuth2 AS boots in-process, discovers, and mints JWTs.
 * No Keycloak; no hand-rolled crypto.
 */
class IdkOauth2AsFixtureTest {
    private lateinit var asFixture: IdkOauth2AsFixture
    private lateinit var client: HttpClient

    @BeforeTest
    fun setUp() {
        asFixture = IdkOauth2AsFixture()
        client = HttpClient(CIO)
    }

    @AfterTest
    fun tearDown() {
        client.close()
        asFixture.stop()
    }

    @Test
    fun boots_and_serves_health_and_openid_configuration() =
        kotlinx.coroutines.test.runTest {
            val health = client.get("${asFixture.baseUrl}/health")
            assertEquals(HttpStatusCode.OK, health.status, health.bodyAsText())

            val discovery = client.get("${asFixture.baseUrl}/.well-known/openid-configuration")
            assertTrue(
                discovery.status.value in 200..299,
                "Expected OIDC discovery. Status=${discovery.status} Body=${discovery.bodyAsText()}",
            )
            val body = discovery.bodyAsText()
            assertTrue(
                body.contains("jwks_uri") || body.contains("issuer"),
                "Discovery body should advertise issuer/jwks. Body=$body",
            )
        }

    @Test
    fun mintAccessToken_is_three_part_jwt_with_tenant_claim() {
        val token = asFixture.mintAccessToken(tenantId = "tenant-a", subject = "user-1")
        val parts = token.split(".")
        assertEquals(3, parts.size, "AS must mint a compact JWT (not opaque)")

        val auth = "Bearer $token"
        assertEquals("tenant-a", BearerTokenSupport.platformTenantFromAuthorizationHeader(auth))
        val claims = BearerTokenSupport.parseClaimsFromAuthorizationHeader(auth)
        assertNotNull(claims)
        assertEquals("tenant-a", PlatformJwtTenantClaims.extractTenantId(claims))
        assertEquals(asFixture.issuer, claims["iss"]?.toString()?.trim('"'))
    }
}

package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.openid.fed.openapi.models.AccountJwk
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PLATFORM e2e with **everything in-process**:
 * - IDK OAuth2 AS ([IdkOauth2AsFixture] / `services-oauth2-as-rest`)
 * - OIDFed admin ([PlatformInProcessFixture] / `configureAdmin`)
 *
 * No standalone PLATFORM admin process. No Keycloak.
 * Requires PostgreSQL (same DB as LEGACY integration tests).
 *
 * ```bash
 * ./gradlew :modules:openid-federation-integration-tests:platformIntegrationTests \
 *   :modules:openid-federation-integration-tests:jvmTest \
 *   --tests "…platform.PlatformAuthApiTest"
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAuthApiTest {

    private lateinit var stack: PlatformInProcessFixture
    private lateinit var client: HttpClient
    private val createdKeyIds = mutableListOf<Pair<String, String>>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl: String get() = stack.adminBaseUrl

    @BeforeAll
    fun bootInProcessStack() {
        try {
            stack = PlatformInProcessFixture()
            println(
                "PLATFORM in-process stack: AS=${stack.asBaseUrl} admin=${stack.adminBaseUrl}",
            )
        } catch (e: Exception) {
            fail(
                "Failed to boot in-process PLATFORM stack (Postgres required): ${e.message}\n" +
                    e.stackTraceToString(),
            )
        }
    }

    @AfterAll
    fun shutdownStack() {
        if (::stack.isInitialized) {
            stack.stop()
        }
    }

    @BeforeTest
    fun setupClient() {
        client = HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @AfterTest
    fun tearDownClient() = runTest {
        for ((tenantId, keyId) in createdKeyIds) {
            try {
                val token = stack.mintAccessToken(tenantId)
                client.delete("$baseUrl/keys/$keyId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            } catch (_: Exception) {
            }
        }
        createdKeyIds.clear()
        client.close()
    }

    @Test
    fun `1 health remains reachable anonymously`() = runTest {
        val response = client.get("$baseUrl/health")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `2 admin mutation without Bearer returns 401`() = runTest {
        val response = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            response.status,
            "Expected 401 without Bearer. Body: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `3 admin mutation with valid IDK AS Bearer succeeds`() = runTest {
        // Account/tenant PK is UUID-typed in SQLDelight
        val tenantA = UUID.randomUUID().toString()
        val token = stack.mintAccessToken(tenantA)

        val response = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("{}")
        }
        assertTrue(
            response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK,
            "Expected 2xx create key with AS Bearer. Status=${response.status} Body=${response.bodyAsText()}",
        )
        val key = json.decodeFromString<AccountJwk>(response.bodyAsText())
        createdKeyIds += tenantA to key.id
    }

    @Test
    fun `4 tenant isolation between AS tokens for tenant A and B`() = runTest {
        val tenantA = UUID.randomUUID().toString()
        val tenantB = UUID.randomUUID().toString()
        val tokenA = stack.mintAccessToken(tenantA)
        val tokenB = stack.mintAccessToken(tenantB)

        val createA = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            setBody("{}")
        }
        assertTrue(
            createA.status == HttpStatusCode.Created || createA.status == HttpStatusCode.OK,
            "Create under A failed: ${createA.status} ${createA.bodyAsText()}",
        )
        val keyA = json.decodeFromString<AccountJwk>(createA.bodyAsText())
        createdKeyIds += tenantA to keyA.id

        val listA = client.get("$baseUrl/keys") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listA.status, listA.bodyAsText())
        assertTrue(listA.bodyAsText().contains(keyA.id), "A should list own key")

        val listB = client.get("$baseUrl/keys") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.OK, listB.status, listB.bodyAsText())
        assertFalse(
            listB.bodyAsText().contains(keyA.id),
            "B must not see A's key. Body: ${listB.bodyAsText()}",
        )
    }

    @Test
    fun `5 invalid and garbage tokens return 401`() = runTest {
        val garbage = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer not-a-jwt")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, garbage.status)

        // Second in-process AS → different issuer / JWKS
        val evilAs = IdkOauth2AsFixture(audience = IdkOauth2AsFixture.DEFAULT_AUDIENCE)
        try {
            val wrongIssToken = evilAs.mintAccessToken(tenantId = "tenant-x")
            val wrongIss = client.post("$baseUrl/keys") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $wrongIssToken")
                setBody("{}")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                wrongIss.status,
                "Token from different AS issuer must 401. Body: ${wrongIss.bodyAsText()}",
            )
        } finally {
            evilAs.stop()
        }
    }

    @Test
    fun `6 accounts endpoint not usable in PLATFORM`() = runTest {
        val token = stack.mintAccessToken(PlatformInProcessFixture.PLATFORM_ROOT_TENANT)
        val response = client.get("$baseUrl/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertTrue(
            response.status == HttpStatusCode.NotFound ||
                response.status == HttpStatusCode.MethodNotAllowed ||
                response.status == HttpStatusCode.Gone ||
                response.status == HttpStatusCode.Unauthorized,
            "PLATFORM must not expose usable /accounts. Status=${response.status} Body=${response.bodyAsText()}",
        )
    }

    @Test
    fun `7 in-process AS discovery stays open without Bearer`() = runTest {
        // Public AS protocol surface (not OIDFed federation server) — proves issuer topology.
        val response = client.get("${stack.asBaseUrl}/.well-known/openid-configuration")
        assertTrue(
            response.status.value in 200..299,
            "AS discovery should work anonymously. Status=${response.status} Body=${response.bodyAsText()}",
        )
    }
}

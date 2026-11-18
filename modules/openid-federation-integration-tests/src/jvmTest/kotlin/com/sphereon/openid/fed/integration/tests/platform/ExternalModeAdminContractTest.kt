package com.sphereon.openid.fed.integration.tests.platform

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Explicitly selected IDK/OIDC mode (`oidf.identity.mode=external`) on the current admin API: the tenant comes from the
 * token issued by the in-process IDK authorization server, the legacy account routes are not registered, and nothing is
 * served without a Bearer token. The suite in [ExternalAuthApiTest] probes the older root level routes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalModeAdminContractTest {
    private lateinit var stack: ExternalInProcessFixture
    private lateinit var client: HttpClient

    @BeforeAll
    fun bootInProcessStack() {
        try {
            stack = ExternalInProcessFixture()
        } catch (e: Exception) {
            fail("Failed to boot the in-process EXTERNAL stack (PostgreSQL required): ${e.message}\n${e.stackTraceToString()}")
        }
    }

    @AfterAll
    fun shutdownStack() {
        if (::stack.isInitialized) stack.stop()
    }

    @BeforeTest
    fun setupClient() {
        client = HttpClient()
    }

    @AfterTest
    fun tearDownClient() {
        client.close()
    }

    private suspend fun get(path: String, token: String? = null, vararg headers: Pair<String, String>): HttpResponse =
        client.get("${stack.adminBaseUrl}$path") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            headers.forEach { (name, value) -> header(name, value) }
        }

    @Test
    fun shouldRequireABearerTokenForTheAdminApi() = runTest {
        assertEquals(HttpStatusCode.Unauthorized, get("/admin/v1/keys").status)
        assertEquals(HttpStatusCode.Unauthorized, get("/admin/v1").status)
    }

    @Test
    fun shouldKeepAnonymousHealthReachable() = runTest {
        assertEquals(HttpStatusCode.OK, get("/health").status)
    }

    @Test
    fun shouldNotRegisterTheLegacyAccountRoutesInExternalMode() = runTest {
        val token = stack.mintAccessToken(UUID.randomUUID().toString())
        val response = get("/accounts", token)
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun shouldAcceptATokenFromTheConfiguredIssuerAndNotAnEntityHeaderOverride() = runTest {
        val tenant = UUID.randomUUID().toString()
        val token = stack.mintAccessToken(tenant)
        val own = get("/admin/v1/keys", token)
        assertNotEquals(HttpStatusCode.Unauthorized, own.status, own.bodyAsText())
        assertNotEquals(HttpStatusCode.Forbidden, own.status, own.bodyAsText())
        assertTrue(own.status.value < 500, "an accepted token must not end in a server error: ${own.status} ${own.bodyAsText()}")

        val withHeader = get("/admin/v1/keys", token, "X-Account-Username" to "root")
        assertEquals(own.status, withHeader.status, "the entity header must not change what the token already selects: ${withHeader.bodyAsText()}")
        assertEquals(own.bodyAsText(), withHeader.bodyAsText(), "the entity header must not change the answer")
    }
}

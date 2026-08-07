package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.openid.fed.integration.tests.adminTestBaseUrl
import com.sphereon.openid.fed.openapi.models.CreateAccount
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import org.junit.jupiter.api.TestInstance
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACCOUNT-mode: only allow-listed JWT principals may use `X-Account-Username` rebind.
 *
 * [AccountInProcessFixture] allow-lists [AccountInProcessFixture.ACCOUNT_E2E_SUBJECT] only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountHeaderAllowListApiTest {

    private lateinit var client: HttpClient
    private val baseUrl: String get() = adminTestBaseUrl()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @BeforeTest
    fun setup() {
        client = HttpClient {
            install(ContentNegotiation) { json(json) }
        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun non_allowlisted_principal_with_entity_header_returns_403() = runTest {
        val fixture = AccountInProcessFixture.shared
        val token =
            fixture.oauthAs.mintAccessToken(
                tenantId = null,
                subject = "not-on-header-allow-list",
            )
        val response =
            client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Account-Username", "root")
                setBody(
                    CreateAccount(
                        username = "should-not-create-${System.currentTimeMillis()}",
                        identifier = "https://example.com/blocked",
                    ),
                )
            }
        assertEquals(
            HttpStatusCode.Forbidden,
            response.status,
            "Body=${response.bodyAsText()}",
        )
        assertTrue(
            response.bodyAsText().contains("not allowed", ignoreCase = true) ||
                response.bodyAsText().contains("forbidden", ignoreCase = true),
            "Expected allow-list denial message. Body=${response.bodyAsText()}",
        )
    }

    @Test
    fun allowlisted_principal_may_use_entity_header() = runTest {
        val fixture = AccountInProcessFixture.shared
        val token = fixture.defaultAccessToken()
        val username = "allowlist-ok-${System.currentTimeMillis()}"
        val create =
            client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                // Operate under root (allow-listed operator) without targeting a foreign entity
                header("X-Account-Username", "root")
                setBody(
                    CreateAccount(
                        username = username,
                        identifier = "https://example.com/$username",
                    ),
                )
            }
        assertTrue(
            create.status == HttpStatusCode.Created || create.status == HttpStatusCode.Conflict,
            "Allow-listed operator should create (or conflict if retry). " +
                "Status=${create.status} Body=${create.bodyAsText()}",
        )
    }
}

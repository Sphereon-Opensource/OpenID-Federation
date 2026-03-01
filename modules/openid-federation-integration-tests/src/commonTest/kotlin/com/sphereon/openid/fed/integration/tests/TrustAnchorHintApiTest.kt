package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.openapi.models.CreateTrustAnchorHint
import com.sphereon.openid.fed.openapi.models.TrustAnchorHint
import com.sphereon.openid.fed.openapi.models.TrustAnchorHintsResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrustAnchorHintApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8081"
        client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
        testUsername = "ta-hint-test-${System.currentTimeMillis()}"

        runTest { createTestAccount() }
    }

    @AfterTest
    fun tearDown() {
        runTest {
            try {
                if (testUsername != null) {
                    client.delete("$baseUrl/accounts") {
                        headers { append("X-Account-Username", testUsername!!) }
                    }
                }
            } catch (e: Exception) {
                println("Cleanup failed: ${e.message}")
            }
        }
        client.close()
    }

    private suspend fun createTestAccount() {
        val response = client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(CreateAccount(username = testUsername!!, identifier = "https://ta-hint-test.com/$testUsername"))
        }
        assertEquals(HttpStatusCode.Created, response.status, "Account creation failed: ${response.bodyAsText()}")
    }

    @Test
    fun `GET trust-anchor-hints should return all hints for account`() = runTest {
        try {
            // Create a hint first
            val identifier = "https://trust-anchor.example.org/${System.currentTimeMillis()}"
            val createResponse = client.post("$baseUrl/trust-anchor-hints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateTrustAnchorHint(identifier = identifier))
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            // Get all hints
            val response = client.get("$baseUrl/trust-anchor-hints") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val hintsResponse = response.body<TrustAnchorHintsResponse>()
            assertTrue(hintsResponse.trustAnchorHints.isNotEmpty(), "Should contain trust anchor hints")
            assertTrue(hintsResponse.trustAnchorHints.any { it.identifier == identifier }, "Should contain created hint")
        } catch (e: Exception) {
            fail("GET /trust-anchor-hints failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-anchor-hints should create a hint`() = runTest {
        try {
            val identifier = "https://trust-anchor-create.example.org/${System.currentTimeMillis()}"
            val response = client.post("$baseUrl/trust-anchor-hints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateTrustAnchorHint(identifier = identifier))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val hint = response.body<TrustAnchorHint>()
            assertEquals(identifier, hint.identifier)
        } catch (e: Exception) {
            fail("POST /trust-anchor-hints failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE trust-anchor-hints should remove a hint`() = runTest {
        try {
            // Create a hint
            val identifier = "https://trust-anchor-delete.example.org/${System.currentTimeMillis()}"
            val createResponse = client.post("$baseUrl/trust-anchor-hints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateTrustAnchorHint(identifier = identifier))
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val created = createResponse.body<TrustAnchorHint>()

            // Delete it
            val deleteResponse = client.delete("$baseUrl/trust-anchor-hints/${created.id}") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify it's gone
            val getResponse = client.get("$baseUrl/trust-anchor-hints") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            val remaining = getResponse.body<TrustAnchorHintsResponse>()
            assertTrue(remaining.trustAnchorHints.none { it.id == created.id }, "Deleted hint should not be present")
        } catch (e: Exception) {
            fail("DELETE /trust-anchor-hints failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-anchor-hints with non-existent account should fail`() = runTest {
        try {
            val response = client.post("$baseUrl/trust-anchor-hints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", "non-existent-user-${System.currentTimeMillis()}") }
                setBody(CreateTrustAnchorHint(identifier = "https://example.org/authority"))
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `DELETE trust-anchor-hints with non-existent ID should fail`() = runTest {
        try {
            val response = client.delete("$baseUrl/trust-anchor-hints/${Uuid.random()}") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }
}

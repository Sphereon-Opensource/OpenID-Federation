package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateCrit
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration tests for the Critical Claim API endpoints.
 *
 * This test class verifies that the critical claim management functionality works correctly,
 * including creating, retrieving, and deleting critical claims for an OpenID Federation entity.
 * The tests ensure that:
 * - Critical claims can be created for an account
 * - Critical claims can be retrieved for an account
 * - Critical claims can be deleted when needed
 * - The API properly handles error cases such as invalid data or non-existent accounts
 *
 * Each test uses a unique account to ensure proper isolation and cleanup.
 */
class CriticalClaimApiTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null

    /**
     * Setup for each test.
     * Creates a new HTTP client with proper configuration and generates a unique test username
     * for each test to ensure test isolation. Also creates a test account.
     */
    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"
        client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
        testUsername = "cc-test-${System.currentTimeMillis()}"

        runTest {
            createTestAccount()
        }
    }

    /**
     * Cleanup after each test.
     * Deletes the test account created in setup to ensure a clean test environment.
     */
    @AfterTest
    fun tearDown() {
        // Clean up by deleting the test account if it was created
        runTest {
            try {
                if (testUsername != null) {
                    client.delete("$baseUrl/accounts") {
                        headers {
                            append("X-Account-Username", testUsername!!)
                        }
                    }
                }
            } catch (e: Exception) {
                println("Cleanup failed: ${e.message}")
            }
        }
        client.close()
    }

    /**
     * Helper method to create a test account for critical claim operations.
     * Creates an account with the generated test username and a default identifier.
     */
    private suspend fun createTestAccount() {
        try {
            val response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = testUsername!!,
                        identifier = "https://test-identifier.com/$testUsername"
                    )
                )
            }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "Account creation failed with status: ${response.status}"
            )
        } catch (e: Exception) {
            fail("Failed to create test account: ${e.message}")
        }
    }

    /**
     * Tests that the POST /crits endpoint creates a new critical claim for an account.
     */
    @Test
    fun `POST crits should create a new critical claim`() = runTest {
        try {
            val claim = "test_claim"
            val response = client.post("$baseUrl/crits") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(CreateCrit(claim = claim))
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains(claim), "Response should contain the created claim")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the GET /crits endpoint returns all critical claims for an account.
     */
    @Test
    fun `GET crits should return all critical claims for account`() = runTest {
        try {
            // First, create a critical claim for this account
            val claim = "test_claim_to_get"
            client.post("$baseUrl/crits") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(CreateCrit(claim = claim))
            }

            // Now get all critical claims
            val response = client.get("$baseUrl/crits") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(claim), "Response should contain the created claim")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the DELETE /crits/{id} endpoint removes a critical claim for an account.
     */
    @Test
    fun `DELETE crits should remove a critical claim`() = runTest {
        try {
            // First, create a critical claim to delete
            val claim = "test_claim_to_delete"
            var response = client.post("$baseUrl/crits") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(CreateCrit(claim = claim))
            }

            assertEquals(HttpStatusCode.Created, response.status, "Failed to create test critical claim")

            val responseBody = response.bodyAsText()
            val responseJson = Json.parseToJsonElement(responseBody).jsonObject
            val claimId = responseJson["id"]?.toString()?.trim('"')
                ?: fail("Failed to get critical claim ID from response")

            println("Extracted critical claim ID: $claimId")

            // Now delete that critical claim
            response = client.delete("$baseUrl/crits/$claimId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(claim), "Response should contain the deleted claim")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the POST /crits endpoint properly handles invalid data.
     */
    @Test
    fun `POST crits with invalid data should fail`() = runTest {
        try {
            val response = client.post("$baseUrl/crits") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody("{}") // Invalid data - missing required field
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the POST /crits endpoint properly handles non-existent accounts.
     */
    @Test
    fun `POST crits with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-user-${System.currentTimeMillis()}"
            val response = client.post("$baseUrl/crits") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", nonExistentUsername)
                }
                setBody(CreateCrit(claim = "test_claim"))
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }
}

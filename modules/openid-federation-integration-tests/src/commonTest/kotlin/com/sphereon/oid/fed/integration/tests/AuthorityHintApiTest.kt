package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.openapi.models.AuthorityHintsResponse
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateAuthorityHint
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

class AuthorityHintApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null

    @BeforeTest
    fun setup() {
        // Configure base URL from environment variable or use default localhost
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"

        // Initialize HTTP client with JSON content negotiation
        client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }

        // Generate a unique test username with timestamp to avoid conflicts
        testUsername = "a-hint-test-${System.currentTimeMillis()}"

        // Create a test account before running tests
        // The account will be used across all test methods
        runTest {
            createTestAccount()
        }
    }

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

    private suspend fun createTestAccount(): String {
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
            return response.bodyAsText()
        } catch (e: Exception) {
            fail("Failed to create test account: ${e.message}")
        }
    }

    @Test
    fun `GET authority-hints should return all hints for account`() = runTest {
        try {
            // First, create a sample authority hint to ensure data exists for the test
            createSampleAuthorityHint()

            // Test retrieval of all authority hints for the current test account
            val response = client.get("$baseUrl/authority-hints") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify successful status code
            assertEquals(HttpStatusCode.OK, response.status)
            // Parse the response and verify it contains authority hints
            val hintsResponse = response.body<AuthorityHintsResponse>()
            assertTrue(hintsResponse.authorityHints.isNotEmpty(), "Response should contain authority hints")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST authority-hints with valid data should create hint`() = runTest {
        try {
            // Create a unique authority identifier using timestamp
            val identifier = "https://authority.example.org/${System.currentTimeMillis()}"

            // Test creating a new authority hint
            val response = client.post("$baseUrl/authority-hints") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateAuthorityHint(
                        identifier = identifier
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify created status code for successful hint creation
            assertEquals(HttpStatusCode.Created, response.status)
            // Parse response and verify the identifier matches what we sent
            val hint = response.body<AuthorityHint>()
            assertEquals(identifier, hint.identifier, "Response should contain the created authority hint")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE authority-hint should remove hint`() = runTest {
        try {
            // First, create an authority hint to delete
            // Using a unique identifier with timestamp
            val identifier = "https://authority-to-delete.example.org/${System.currentTimeMillis()}"
            var response = client.post("$baseUrl/authority-hints") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateAuthorityHint(
                        identifier = identifier
                    )
                )
            }

            // Verify creation was successful
            assertEquals(HttpStatusCode.Created, response.status, "Failed to create test authority hint")
            val createdHint = response.body<AuthorityHint>()
            val hintId = createdHint.id

            // Now test deleting the authority hint we just created
            response = client.delete("$baseUrl/authority-hints/$hintId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Delete status code: ${response.status}")
            println("Delete response body: ${response.bodyAsText()}")

            // Verify successful deletion
            assertEquals(HttpStatusCode.OK, response.status)
            val deletedHint = response.body<AuthorityHint>()
            assertEquals(identifier, deletedHint.identifier, "Response should contain the deleted authority hint")

            // Verify the hint is actually gone by getting all hints again
            // and confirming the deleted hint is not in the list
            response = client.get("$baseUrl/authority-hints") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            val hintsResponse = response.body<AuthorityHintsResponse>()
            assertTrue(hintsResponse.authorityHints.none { it.id == hintId }, "Deleted hint should not be present")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST authority-hints with non-existent account should fail`() = runTest {
        try {
            // Generate a username that doesn't exist in the system
            val nonExistentUsername = "non-existent-user-${System.currentTimeMillis()}"

            // Test creating an authority hint with a non-existent account
            // This should fail with a 404 Not Found error
            val response = client.post("$baseUrl/authority-hints") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", nonExistentUsername)
                }
                setBody(
                    CreateAuthorityHint(
                        identifier = "https://example.org/authority"
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify not found status for non-existent account
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `DELETE authority-hint with non-existent ID should fail`() = runTest {
        try {
            // Generate a random UUID that doesn't correspond to any existing authority hint
            val nonExistentId = Uuid.random().toString()

            // Test deleting an authority hint with a non-existent ID
            // This should fail with a 404 Not Found error
            val response = client.delete("$baseUrl/authority-hints/$nonExistentId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify not found status for non-existent hint ID
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    private suspend fun createSampleAuthorityHint() {
        val identifier = "https://sample-authority.example.org/${System.currentTimeMillis()}"
        val response = client.post("$baseUrl/authority-hints") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateAuthorityHint(
                    identifier = identifier
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample authority hint")
    }
}

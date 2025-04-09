package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateMetadata
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration tests for the Metadata API endpoints.
 *
 * This test class verifies that the metadata management functionality works correctly,
 * including creating, retrieving, and deleting metadata for an OpenID Federation entity.
 * The tests ensure that:
 * - Metadata can be created for an account
 * - Metadata can be retrieved for an account
 * - Metadata can be deleted when needed
 * - The API properly handles error cases such as invalid JSON or non-existent accounts
 *
 * Each test uses a unique account to ensure proper isolation and cleanup.
 */
class MetadataApiTest {
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
        testUsername = "metadata-test-${System.currentTimeMillis()}"
        // Create a test account to be used in metadata tests
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
     * Helper method to create a test account for metadata operations.
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
     * Tests that the GET /metadata endpoint returns all metadata for an account.
     * First creates sample metadata, then verifies it can be retrieved correctly.
     */
    @Test
    fun `GET metadata should return all metadata for account`() = runTest {
        try {
            // First, create some metadata for this account
            createSampleMetadata()

            // Now get all metadata
            val response = client.get("$baseUrl/metadata") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("metadata"), "Response should contain metadata")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the POST /metadata endpoint creates new metadata for an account.
     * Verifies that metadata for an OpenID Provider can be created successfully.
     */
    @Test
    fun `POST metadata with valid data should create metadata`() = runTest {
        try {
            val metadataKey = "openid_provider"
            val metadataJson = buildJsonObject {
                put("issuer", JsonPrimitive("https://test-issuer.com/$testUsername"))
                put("authorization_endpoint", JsonPrimitive("https://test-issuer.com/$testUsername/authorize"))
                put("token_endpoint", JsonPrimitive("https://test-issuer.com/$testUsername/token"))
                putJsonArray("response_types_supported") {
                    add(JsonPrimitive("code"))
                    add(JsonPrimitive("token"))
                }
            }

            val response = client.post("$baseUrl/metadata") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateMetadata(
                        key = metadataKey,
                        metadata = metadataJson
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains(metadataKey), "Response should contain the metadata key")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the DELETE /metadata endpoint removes metadata for an account.
     * Creates test metadata, then deletes it and verifies the deletion was successful.
     */
    @Test
    fun `DELETE metadata should remove metadata entry`() = runTest {
        try {
            // First, create metadata to delete
            val metadataKey = "openid_provider_to_delete"
            val metadataJson = buildJsonObject {
                put("issuer", JsonPrimitive("https://delete-test.com/$testUsername"))
            }

            var response = client.post("$baseUrl/metadata") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateMetadata(
                        key = metadataKey,
                        metadata = metadataJson
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status, "Failed to create test metadata")

            val responseBody = response.bodyAsText()
            val responseJson = Json.parseToJsonElement(responseBody).jsonObject
            val metadataId = responseJson["id"]?.toString()?.trim('"')
                ?: fail("Failed to get metadata ID from response")

            println("Extracted metadata ID: $metadataId")

            // Now delete that metadata
            response = client.delete("$baseUrl/metadata/$metadataId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(metadataKey), "Response should contain the deleted metadata key")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the POST /metadata endpoint properly handles invalid JSON.
     * Verifies that the endpoint responds with HTTP 400 Bad Request when invalid JSON is provided.
     */
    @Test
    fun `POST metadata with invalid JSON should fail`() = runTest {
        try {
            val response = client.post("$baseUrl/metadata") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody("{\"key\": \"invalid\", \"metadata\": \"not-a-json-object\"}")
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Tests that the POST /metadata endpoint properly handles non-existent accounts.
     * Verifies that the endpoint responds with HTTP 404 Not Found when a non-existent username is provided.
     */
    @Test
    fun `POST metadata with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-user-${System.currentTimeMillis()}"
            val metadataJson = buildJsonObject {
                put("issuer", JsonPrimitive("https://test.com"))
            }

            val response = client.post("$baseUrl/metadata") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", nonExistentUsername)
                }
                setBody(
                    CreateMetadata(
                        key = "openid_provider",
                        metadata = metadataJson
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    /**
     * Helper method to create sample metadata for testing.
     * Creates a basic OpenID Provider metadata entry to be used in tests.
     */
    private suspend fun createSampleMetadata() {
        val metadataKey = "openid_provider"
        val metadataJson = buildJsonObject {
            put("issuer", JsonPrimitive("https://sample-issuer.com/$testUsername"))
            put("authorization_endpoint", JsonPrimitive("https://sample-issuer.com/$testUsername/authorize"))
        }

        val response = client.post("$baseUrl/metadata") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadata(
                    key = metadataKey,
                    metadata = metadataJson
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample metadata")
    }
}

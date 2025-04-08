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

class MetadataTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null

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

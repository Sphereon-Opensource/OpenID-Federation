package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkTypesResponse
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

class TrustMarkTypeApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"
        client = HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        testUsername = "tmt-test-${System.currentTimeMillis()}"

        // Create a test account to be used in trust mark type tests
        runTest { createTestAccount() }
    }

    @AfterTest
    fun tearDown() {
        // Clean up by deleting the test account if it was created
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

    private suspend fun createTestAccount(): String {
        try {
            val response =
                client.post("$baseUrl/accounts") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateAccount(
                            username = testUsername!!,
                            identifier = "https://tmt-test-identifier.com/$testUsername"
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
            fail("Failed to create test account for TMT test: ${e.message}")
        }
    }

    private suspend fun createSampleTrustMarkType(
        identifier: String = "https://sample-tmt.org/${System.currentTimeMillis()}"
    ): TrustMarkType {
        val response =
            client.post("$baseUrl/trust-mark-types") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(
                    CreateTrustMarkType(
                        identifier = identifier
                    )
                )
            }
        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample TMT")
        return response.body<TrustMarkType>()
    }

    @Test
    fun `GET trust-mark-types should return all types for account`() = runTest {
        try {
            createSampleTrustMarkType() // Ensure at least one exists

            val response =
                client.get("$baseUrl/trust-mark-types") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("GET /trust-mark-types Status: ${response.status}")
            println("GET /trust-mark-types Body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val typesResponse = response.body<TrustMarkTypesResponse>()
            assertTrue(
                typesResponse.trustMarkTypes.isNotEmpty(),
                "Response should contain trust mark types"
            )
        } catch (e: Exception) {
            fail("GET /trust-mark-types request failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-mark-types with valid data should create type`() = runTest {
        try {
            val identifier = "https://new-tmt.org/${System.currentTimeMillis()}"
            val name = "New TMT"
            val uri = "https://new-tmt.org/uri"

            val response =
                client.post("$baseUrl/trust-mark-types") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Account-Username", testUsername!!) }
                    setBody(
                        CreateTrustMarkType(identifier = identifier)
                    )
                }

            println("POST /trust-mark-types Status: ${response.status}")
            println("POST /trust-mark-types Body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Created, response.status)
            val createdType = response.body<TrustMarkType>()
            assertEquals(identifier, createdType.identifier)
        } catch (e: Exception) {
            fail("POST /trust-mark-types request failed: ${e.message}")
        }
    }

    @Test
    fun `GET trust-mark-types by ID should return specific type`() = runTest {
        try {
            val sampleType = createSampleTrustMarkType()
            val typeId = sampleType.id

            val response =
                client.get("$baseUrl/trust-mark-types/$typeId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("GET /trust-mark-types/$typeId Status: ${response.status}")
            println("GET /trust-mark-types/$typeId Body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val fetchedType = response.body<TrustMarkType>()
            assertEquals(sampleType.id, fetchedType.id)
            assertEquals(sampleType.identifier, fetchedType.identifier)
        } catch (e: Exception) {
            fail("GET /trust-mark-types/{id} request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE trust-mark-types should remove type entry`() = runTest {
        try {
            val typeToDelete =
                createSampleTrustMarkType(
                    "https://tmt-to-delete.org/${System.currentTimeMillis()}"
                )
            val typeId = typeToDelete.id

            // Delete the type
            val deleteResponse =
                client.delete("$baseUrl/trust-mark-types/$typeId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("DELETE /trust-mark-types/$typeId Status: ${deleteResponse.status}")
            println("DELETE /trust-mark-types/$typeId Body: ${deleteResponse.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, deleteResponse.status)
            val deletedType = deleteResponse.body<TrustMarkType>()
            assertEquals(typeId, deletedType.id)

            // Verify it's gone by trying to get it
            val getResponse =
                client.get("$baseUrl/trust-mark-types/$typeId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }
            assertEquals(
                HttpStatusCode.NotFound,
                getResponse.status,
                "Deleted type should not be found"
            )
        } catch (e: Exception) {
            fail("DELETE /trust-mark-types request failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-mark-types with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-tmt-user-${System.currentTimeMillis()}"
            val response =
                client.post("$baseUrl/trust-mark-types") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Account-Username", nonExistentUsername) }
                    setBody(
                        CreateTrustMarkType(
                            identifier = "https://fail-tmt.org",
                        )
                    )
                }

            println("POST /trust-mark-types (Non-existent Acc) Status: ${response.status}")
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail(
                "POST /trust-mark-types (Non-existent Acc) request failed unexpectedly: ${e.message}"
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `GET trust-mark-types with non-existent ID should fail`() = runTest {
        try {
            val nonExistentId = Uuid.random().toString()
            val response =
                client.get("$baseUrl/trust-mark-types/$nonExistentId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("GET /trust-mark-types (Non-existent ID) Status: ${response.status}")
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail(
                "GET /trust-mark-types (Non-existent ID) request failed unexpectedly: ${e.message}"
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `DELETE trust-mark-types with non-existent ID should fail`() = runTest {
        try {
            val nonExistentId = Uuid.random().toString()
            val response =
                client.delete("$baseUrl/trust-mark-types/$nonExistentId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("DELETE /trust-mark-types (Non-existent ID) Status: ${response.status}")
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail(
                "DELETE /trust-mark-types (Non-existent ID) request failed unexpectedly: ${e.message}"
            )
        }
    }
}

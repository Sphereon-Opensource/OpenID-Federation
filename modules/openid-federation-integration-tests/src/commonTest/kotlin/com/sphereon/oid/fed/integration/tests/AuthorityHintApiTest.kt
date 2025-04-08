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
        testUsername = "a-hint-test-${System.currentTimeMillis()}"

        // Create a test account to be used in authority hint tests
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
            // First, create some authority hints for this account
            createSampleAuthorityHint()

            // Now get all authority hints
            val response = client.get("$baseUrl/authority-hints") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val hintsResponse = response.body<AuthorityHintsResponse>()
            assertTrue(hintsResponse.authorityHints.isNotEmpty(), "Response should contain authority hints")
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST authority-hints with valid data should create hint`() = runTest {
        try {
            val identifier = "https://authority.example.org/${System.currentTimeMillis()}"
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

            assertEquals(HttpStatusCode.Created, response.status)
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

            assertEquals(HttpStatusCode.Created, response.status, "Failed to create test authority hint")
            val createdHint = response.body<AuthorityHint>()
            val hintId = createdHint.id

            // Now delete that authority hint
            response = client.delete("$baseUrl/authority-hints/$hintId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Delete status code: ${response.status}")
            println("Delete response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val deletedHint = response.body<AuthorityHint>()
            assertEquals(identifier, deletedHint.identifier, "Response should contain the deleted authority hint")

            // Verify it's really gone by getting all hints
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
            val nonExistentUsername = "non-existent-user-${System.currentTimeMillis()}"
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

            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `DELETE authority-hint with non-existent ID should fail`() = runTest {
        try {
            val nonExistentId = Uuid.random().toString()
            val response = client.delete("$baseUrl/authority-hints/$nonExistentId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

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

package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarksResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ReceivedTrustMarkApiTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testTrustMarkId: String? = null
    private var testJwt: String? = null

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
        testUsername = "rtm-test-${System.currentTimeMillis()}"
        testTrustMarkId = "https://example.com/trust-mark-${System.currentTimeMillis()}"
        testJwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        runTest {
            createTestAccount()
        }
    }

    @AfterTest
    fun tearDown() {
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
    fun `POST received-trust-marks should create a new received trust mark`() = runTest {
        try {
            val response = client.post("$baseUrl/received-trust-marks") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateReceivedTrustMark(
                        trustMarkId = testTrustMarkId!!,
                        jwt = testJwt!!
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val createdTrustMark = response.body<ReceivedTrustMark>()
            assertNotNull(createdTrustMark.id)
            assertEquals(testTrustMarkId, createdTrustMark.trustMarkId)
            assertEquals(testJwt, createdTrustMark.jwt)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `GET received-trust-marks should return all received trust marks for account`() = runTest {
        try {
            // First, create a received trust mark for this account
            client.post("$baseUrl/received-trust-marks") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateReceivedTrustMark(
                        trustMarkId = testTrustMarkId!!,
                        jwt = testJwt!!
                    )
                )
            }

            // Now get all received trust marks
            val response = client.get("$baseUrl/received-trust-marks") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val trustMarksResponse = response.body<ReceivedTrustMarksResponse>()
            assertTrue(trustMarksResponse.receivedTrustMarks.isNotEmpty())
            assertTrue(trustMarksResponse.receivedTrustMarks.any { it.trustMarkId == testTrustMarkId })
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE received-trust-marks should remove a received trust mark`() = runTest {
        try {
            // First, create a received trust mark to delete
            val createResponse = client.post("$baseUrl/received-trust-marks") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateReceivedTrustMark(
                        trustMarkId = testTrustMarkId!!,
                        jwt = testJwt!!
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val createdTrustMark = createResponse.body<ReceivedTrustMark>()
            val trustMarkIdToDelete = createdTrustMark.id

            // Now delete that received trust mark
            val deleteResponse = client.delete("$baseUrl/received-trust-marks/$trustMarkIdToDelete") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            assertEquals(HttpStatusCode.OK, deleteResponse.status)
            val deletedTrustMark = deleteResponse.body<ReceivedTrustMark>()
            assertEquals(trustMarkIdToDelete, deletedTrustMark.id)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST received-trust-marks with invalid data should fail`() = runTest {
        try {
            val response = client.post("$baseUrl/received-trust-marks") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody("{}") // Invalid data - missing required fields
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST received-trust-marks with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-user-${System.currentTimeMillis()}"
            val response = client.post("$baseUrl/received-trust-marks") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", nonExistentUsername)
                }
                setBody(
                    CreateReceivedTrustMark(
                        trustMarkId = testTrustMarkId!!,
                        jwt = testJwt!!
                    )
                )
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }
}

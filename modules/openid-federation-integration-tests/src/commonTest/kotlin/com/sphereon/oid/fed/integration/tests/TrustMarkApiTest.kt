package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkResult
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarksResponse
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrustMarkApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testAccountIdentifier: String? = null
    private var createdKeyId: String? = null
    private var createdTrustMarkTypeId: String? = null

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"
        client = HttpClient {
            install(ContentNegotiation) { json(json) }
            // Default request fails on non-2xx responses. Add expectSuccess = false where needed.
        }
        testUsername = "tm-test-${System.currentTimeMillis()}"
        testAccountIdentifier = "https://tm-test-identifier.com/$testUsername"

        // Create a test account to be used in trust mark tests
        runTest {
            createTestAccount()
            // Pre-create a key and a trust mark type needed for creating trust marks
            createdKeyId = createSampleKey().kid
            createdTrustMarkTypeId = createSampleTrustMarkType().id
        }
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
                println("Cleanup failed for TrustMark test: ${e.message}")
            }
        }
        client.close()
    }

    private suspend fun createTestAccount() {
        try {
            val response =
                client.post("$baseUrl/accounts") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateAccount(
                            username = testUsername!!,
                            identifier = testAccountIdentifier!!
                        )
                    )
                }
            assertEquals(HttpStatusCode.Created, response.status, "Account creation failed")

            val key = client.post("$baseUrl/keys") { contentType(ContentType.Application.Json) }

            assertEquals(HttpStatusCode.Created, key.status, "Key creation failed")
        } catch (e: Exception) {
            fail("Failed to create test account for TM test: ${e.message}")
        }
    }

    private suspend fun createSampleKey(): AccountJwk {
        val response =
            client.post("$baseUrl/keys") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateKey()) // Assuming default key creation is sufficient
            }
        assertEquals(HttpStatusCode.Created, response.status, "Key creation failed")
        return response.body<AccountJwk>()
    }

    private suspend fun createSampleTrustMarkType(): TrustMarkType {
        val identifier = "https://sample-tmt-for-tm.org/${System.currentTimeMillis()}"
        val response =
            client.post("$baseUrl/trust-mark-types") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateTrustMarkType(identifier = identifier))
            }
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Failed to create sample TMT for TM test"
        )
        return response.body()
    }

    private suspend fun createSampleTrustMark(): CreateTrustMarkResult {
        assertNotNull(createdKeyId, "Key ID should be created in setup")
        assertNotNull(createdTrustMarkTypeId, "TrustMarkType ID should be created in setup")

        val requestBody =
            CreateTrustMarkRequest(
                trustMarkId = createdTrustMarkTypeId!!, // The ID of the TrustMarkType
                sub = testAccountIdentifier!! // Entity itself for simplicity
            )

        val response =
            client.post("$baseUrl/trust-marks") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(requestBody)
            }
        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample Trust Mark")
        return response.body<CreateTrustMarkResult>() // Deserialize to TrustMark object
    }

    @Test
    fun `GET trust-marks should return all marks for account`() = runTest {
        try {
            val createdMarkJwt = createSampleTrustMark()
            assertNotNull(createdMarkJwt)

            val response =
                client.get("$baseUrl/trust-marks") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("GET /trust-marks Status: ${response.status}")

            assertEquals(HttpStatusCode.OK, response.status)
            val marksResponse = response.body<TrustMarksResponse>()
            assertTrue(
                marksResponse.trustMarks?.isNotEmpty() == true,
                "Response should contain trust marks"
            )
        } catch (e: Exception) {
            fail("GET /trust-marks request failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-marks with valid data should create mark JWT`() = runTest {
        try {
            assertNotNull(createdKeyId, "Key ID should be created in setup")
            assertNotNull(createdTrustMarkTypeId, "TrustMarkType ID should be created in setup")

            val requestBody =
                CreateTrustMarkRequest(
                    trustMarkId = createdTrustMarkTypeId!!,
                    sub = testAccountIdentifier!!
                )

            val response =
                client.post("$baseUrl/trust-marks") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Account-Username", testUsername!!) }
                    setBody(requestBody)
                }

            println("POST /trust-marks Status: ${response.status}")

            assertEquals(HttpStatusCode.Created, response.status)
        } catch (e: Exception) {
            fail("POST /trust-marks request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE trust-marks should remove mark entry`() = runTest {
        try {
            // 1. Create a mark and get its object
            val createdMark = createSampleTrustMark()
            assertNotNull(createdMark)
            val markIdToDelete = createdMark.id!!

            // 2. Delete the mark
            val deleteResponse =
                client.delete("$baseUrl/trust-marks/$markIdToDelete") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("DELETE /trust-marks/$markIdToDelete Status: ${deleteResponse.status}")
            println("DELETE /trust-marks/$markIdToDelete Body: ${deleteResponse.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, deleteResponse.status)
            val deletedMark = deleteResponse.body<CreateTrustMarkResult>()
            assertEquals(markIdToDelete, deletedMark.id)

            // 3. Verify it's gone
            val verifyGetResponse =
                client.get("$baseUrl/trust-marks") {
                    headers { append("X-Account-Username", testUsername!!) }
                }
            assertEquals(HttpStatusCode.OK, verifyGetResponse.status)
            val remainingMarks = verifyGetResponse.body<TrustMarksResponse>().trustMarks
            assertTrue(
                remainingMarks?.none { it.id == markIdToDelete } == true,
                "Deleted mark should not be present"
            )
        } catch (e: Exception) {
            fail("DELETE /trust-marks request failed: ${e.message}")
        }
    }

    @Test
    fun `POST trust-marks with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-tm-user-${System.currentTimeMillis()}"
            val requestBody =
                CreateTrustMarkRequest(
                    trustMarkId = createdTrustMarkTypeId ?: "dummy-tmt-id",
                    sub = "subject"
                )

            try {
                val response =
                    client.post("$baseUrl/trust-marks") {
                        contentType(ContentType.Application.Json)
                        headers { append("X-Account-Username", nonExistentUsername) }
                        setBody(requestBody)
                    }

                println("POST /trust-marks (Non-existent Acc) Status: ${response.status}")
                assertEquals(HttpStatusCode.NotFound, response.status) // Expecting 404 Not Found
            } catch (e: Exception) {
                // Expected to fail with a 404, so this is actually a success case for this test
                println("Expected exception occurred: ${e.message}")
            }
        } catch (e: Exception) {
            fail("POST /trust-marks (Non-existent Acc) test failed unexpectedly: ${e.message}")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `DELETE trust-marks with non-existent ID should fail`() = runTest {
        try {
            val nonExistentId = Uuid.random().toString()
            val response =
                client.delete("$baseUrl/trust-marks/$nonExistentId") {
                    headers { append("X-Account-Username", testUsername!!) }
                }

            println("DELETE /trust-marks (Non-existent ID) Status: ${response.status}")
            assertEquals(HttpStatusCode.NotFound, response.status) // Expecting 404 Not Found
        } catch (e: Exception) {
            fail("DELETE /trust-marks (Non-existent ID) request failed unexpectedly: ${e.message}")
        }
    }
}

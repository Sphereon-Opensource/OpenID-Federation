package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateSubordinate
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.openapi.models.Subordinate
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwksResponse
import com.sphereon.oid.fed.openapi.models.SubordinatesResponse
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

class SubordinateApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testSubordinateId: String? = null
    private var testSubordinateJwkId: String? = null

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true // Important for potentially null fields in Jwk
    }

    // Sample JWK for testing - Replace with actual valid JWK structure if needed
    private val sampleJwk =
        Jwk(
            kty = "RSA",
            e = "AQAB",
            n = "unique-modulus-for-${System.currentTimeMillis()}", // Ensure uniqueness
            // for test runs
            kid = "test-subordinate-jwk-${System.currentTimeMillis()}"
        )

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"
        client = HttpClient { install(ContentNegotiation) { json(json) } }
        testUsername = "subordinate-test-${System.currentTimeMillis()}"
    }

    @AfterTest
    fun tearDown() = runTest {
        // Clean up resources in reverse order of creation
        try {
            if (testUsername != null) {
                // Delete subordinate if created
                if (testSubordinateId != null) {
                    // Delete subordinate JWK if created
                    if (testSubordinateJwkId != null) {
                        deleteSubordinateJwk(testSubordinateId!!, testSubordinateJwkId!!)
                    }
                    deleteSubordinate(testSubordinateId!!)
                }
                // Delete the test account
                deleteTestAccount()
            }
        } catch (e: Exception) {
            println("Cleanup failed: ${e.message}")
            // Log error, but don't fail the test run due to cleanup issues
        } finally {
            client.close()
        }
    }

    @Test
    fun `Full subordinate management flow test`() = runTest {
        try {
            // Step 1: Create a new account
            createTestAccount()

            // Step 2: Create a subordinate for this account
            val subordinate = createSubordinate()
            assertNotNull(subordinate, "Subordinate creation failed")
            testSubordinateId = subordinate.id // Store the ID for later use

            // Step 3: Get subordinates for the account
            val subordinatesResponse = getSubordinates()
            assertNotNull(subordinatesResponse, "Failed to get subordinates")
            assertTrue(
                subordinatesResponse.subordinates.any { it.id == testSubordinateId },
                "Created subordinate not found in list"
            )

            // Step 4: Create a JWK for the subordinate
            val subordinateJwk = createSubordinateJwk(testSubordinateId!!)
            assertNotNull(subordinateJwk, "Subordinate JWK creation failed")
            testSubordinateJwkId = subordinateJwk.id // Store the JWK ID

            // Step 5: Get JWKs for the subordinate
            val subordinateJwksResponse = getSubordinateJwks(testSubordinateId!!)
            assertNotNull(subordinateJwksResponse, "Failed to get subordinate JWKs")
            assertTrue(
                subordinateJwksResponse.jwks.any { it.id == testSubordinateJwkId },
                "Created subordinate JWK not found"
            )

//            // Step 6: Get the subordinate statement
//            val subordinateStatement = getSubordinateStatement(testSubordinateId!!)
//            assertNotNull(subordinateStatement, "Subordinate statement retrieval failed")
//            // Basic check: Statement should not be empty. More specific checks can be added.
//            assertTrue(
//                    subordinateStatement.statement.isNotBlank(),
//                    "Subordinate statement is blank"
//            )
//            println("Retrieved subordinate statement: ${subordinateStatement.statement}")

            // Step 7: Publish the subordinate statement (dry run)
            val publishResult = publishSubordinateStatement(testSubordinateId!!)
            assertNotNull(publishResult, "Subordinate statement publishing (dry run) failed")
            // Basic check: Result should not be empty. More specific checks can be added.
            assertTrue(publishResult.isNotBlank(), "Publish dry run result is blank")
            println("Publish dry run result: $publishResult")

            // Step 8: Delete the subordinate JWK
            val deletedJwk = deleteSubordinateJwk(testSubordinateId!!, testSubordinateJwkId!!)
            assertNotNull(deletedJwk, "Subordinate JWK deletion failed")
            assertEquals(testSubordinateJwkId, deletedJwk.id, "Deleted JWK ID does not match")
            testSubordinateJwkId = null // Clear the ID after successful deletion

            // Step 9: Delete the subordinate
            val deletedSubordinate = deleteSubordinate(testSubordinateId!!)
            assertNotNull(deletedSubordinate, "Subordinate deletion failed")
            assertEquals(
                testSubordinateId,
                deletedSubordinate.id,
                "Deleted subordinate ID does not match"
            )
            testSubordinateId = null // Clear the ID after successful deletion
        } catch (e: Exception) {
            e.printStackTrace() // Print stack trace for better debugging
            fail("Subordinate management flow test failed: ${e.message}")
        }
    }

    // --- Helper Functions ---

    private suspend fun createTestAccount(): String {
        val response =
            client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = testUsername!!,
                        identifier = "https://subordinate-test-id.com/$testUsername"
                    )
                )
            }
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Account creation failed: ${response.bodyAsText()}"
        )

        println("Created test account: $testUsername")

        val key = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            headers { append("X-Account-Username", testUsername!!) }
        }

        assertEquals(HttpStatusCode.Created, key.status, "Key creation failed")

        return response.bodyAsText()
    }

    private suspend fun deleteTestAccount() {
        val response =
            client.delete("$baseUrl/accounts") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        // Allow Not Found as well, in case cleanup runs after a failure before account deletion
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound,
            "Account deletion failed: ${response.status}"
        )
        println("Deleted test account: $testUsername")
    }

    private suspend fun createSubordinate(): Subordinate {
        val subordinateIdentifier = "https://sub.test.com/${System.currentTimeMillis()}"
        println(
            "Creating subordinate with identifier: $subordinateIdentifier for account $testUsername"
        )
        val response =
            client.post("$baseUrl/subordinates") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateSubordinate(identifier = subordinateIdentifier))
            }
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Subordinate creation failed: ${response.bodyAsText()}"
        )
        val subordinate: Subordinate = response.body()
        println("Created subordinate: ${subordinate.id}")
        return subordinate
    }

    private suspend fun getSubordinates(): SubordinatesResponse {
        println("Getting subordinates for account $testUsername")
        val response =
            client.get("$baseUrl/subordinates") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Getting subordinates failed: ${response.bodyAsText()}"
        )
        return response.body()
    }

    private suspend fun createSubordinateJwk(subordinateId: String): SubordinateJwk {
        println("Creating JWK for subordinate $subordinateId (Account: $testUsername)")
        val response =
            client.post("$baseUrl/subordinates/$subordinateId/jwks") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(sampleJwk)
            }
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Subordinate JWK creation failed: ${response.bodyAsText()}"
        )
        val subordinateJwk: SubordinateJwk = response.body()
        println("Created subordinate JWK: ${subordinateJwk.id}")
        return subordinateJwk
    }

    private suspend fun getSubordinateJwks(subordinateId: String): SubordinateJwksResponse {
        println("Getting JWKs for subordinate $subordinateId (Account: $testUsername)")
        val response =
            client.get("$baseUrl/subordinates/$subordinateId/jwks") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Getting subordinate JWKs failed: ${response.bodyAsText()}"
        )
        return response.body()
    }

    private suspend fun deleteSubordinateJwk(subordinateId: String, jwkId: String): SubordinateJwk {
        println("Deleting JWK $jwkId for subordinate $subordinateId (Account: $testUsername)")
        val response =
            client.delete("$baseUrl/subordinates/$subordinateId/jwks/$jwkId") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Subordinate JWK deletion failed: ${response.bodyAsText()}"
        )
        println("Deleted subordinate JWK: $jwkId")
        return response.body()
    }

    private suspend fun getSubordinateStatement(
        subordinateId: String
    ): com.sphereon.oid.fed.openapi.models.SubordinateStatement {
        println("Getting statement for subordinate $subordinateId (Account: $testUsername)")
        val response =
            client.get("$baseUrl/subordinates/$subordinateId/statement") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Getting subordinate statement failed: ${response.bodyAsText()}"
        )
        return response.body()
    }

    private suspend fun publishSubordinateStatement(subordinateId: String): String {
        println(
            "Publishing statement (dry run) for subordinate $subordinateId (Account: $testUsername)"
        )
        val response =
            client.post("$baseUrl/subordinates/$subordinateId/statement") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(
                    PublishStatementRequest( // Use actual request body
                        dryRun = true
                        // kid and kmsKeyRef are optional, let service decide default
                        // for test
                    )
                )
            }
        // Dry run returns OK, actual publish returns Created
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Publishing subordinate statement (dry run) failed: ${response.bodyAsText()}"
        )
        return response.bodyAsText()
    }

    private suspend fun deleteSubordinate(subordinateId: String): Subordinate {
        println("Deleting subordinate $subordinateId (Account: $testUsername)")
        val response =
            client.delete("$baseUrl/subordinates/$subordinateId") {
                headers { append("X-Account-Username", testUsername!!) }
            }
        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Subordinate deletion failed: ${response.bodyAsText()}"
        )
        println("Deleted subordinate: $subordinateId")
        return response.body()
    }
}

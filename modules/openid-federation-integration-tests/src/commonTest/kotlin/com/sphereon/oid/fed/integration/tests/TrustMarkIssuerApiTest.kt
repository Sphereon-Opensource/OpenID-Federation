package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuerRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkIssuer
import com.sphereon.oid.fed.openapi.models.TrustMarkIssuersResponse
import com.sphereon.oid.fed.openapi.models.TrustMarkType
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

class TrustMarkIssuerApiTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testTrustMarkTypeId: String? = null
    private var testIssuerIdentifier: String? = null

    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

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
        testUsername = "tm-issuer-test-${System.currentTimeMillis()}"
        testIssuerIdentifier = "https://test-issuer.com/${System.currentTimeMillis()}"

        // Account will be created in the test methods when needed

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

    @Test
    fun `Trust mark issuer operations test`() = runTest {
        try {
            // Step 1: Create a test account
            createTestAccount()

            // Step 2: Create a trust mark type
            val trustMarkType = createSampleTrustMarkType()
            assertNotNull(trustMarkType, "Trust mark type creation failed")

            println("Created trust mark type with ID: ${trustMarkType.id}")

            // Step 3: Add an issuer to the trust mark type
            val issuer = addIssuerToTrustMarkType(trustMarkType.id)

            assertNotNull(issuer, "Failed to add issuer to trust mark type")

            // Step 4: Get issuers for the trust mark type
            val issuers = getIssuersForTrustMarkType(trustMarkType.id)
            println(issuers)
            assertNotNull(issuers, "Failed to get issuers for trust mark type")

            // Verify the issuer is in the list
            val issuersList = json.decodeFromString<TrustMarkIssuersResponse>(issuers)
            assertTrue(
                issuersList.issuers.any { it.id == issuer.id },
                "Added issuer not found in issuers list"
            )

            // Step 5: Remove the issuer from the trust mark type
            val removalResult = removeIssuerFromTrustMarkType(trustMarkType.id, issuer.id!!)
            println(removalResult)
            assertNotNull(removalResult, "Failed to remove issuer from trust mark type")

            // Step 6: Verify the issuer was removed
            val updatedIssuers = getIssuersForTrustMarkType(trustMarkType.id)
            val updatedIssuersList = json.decodeFromString<TrustMarkIssuersResponse>(updatedIssuers)
            assertTrue(
                updatedIssuersList.issuers.none { it.id == issuer.id },
                "Issuer was not removed"
            )

        } catch (e: Exception) {
            fail("Trust mark issuer test failed: ${e.message}")
        }
    }

    private suspend fun addIssuerToTrustMarkType(trustMarkTypeId: String): TrustMarkIssuer {
        val response = client.post("$baseUrl/trust-mark-types/$trustMarkTypeId/issuers") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateTrustMarkTypeIssuerRequest(
                    identifier = testIssuerIdentifier!!
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status, "Adding issuer failed with status: ${response.status}")
        return json.decodeFromString<TrustMarkIssuer>(response.bodyAsText())
    }

    private suspend fun getIssuersForTrustMarkType(trustMarkTypeId: String): String {
        val response = client.get("$baseUrl/trust-mark-types/$trustMarkTypeId/issuers") {
            headers {
                append("X-Account-Username", testUsername!!)
            }
        }

        assertEquals(HttpStatusCode.OK, response.status, "Getting issuers failed with status: ${response.status}")
        return response.bodyAsText()
    }

    private suspend fun removeIssuerFromTrustMarkType(trustMarkTypeId: String, issuerId: String): String {
        val response = client.delete("$baseUrl/trust-mark-types/$trustMarkTypeId/issuers/$issuerId") {
            headers {
                append("X-Account-Username", testUsername!!)
            }
        }

        println("Response body: ${response.bodyAsText()}")
        assertEquals(HttpStatusCode.OK, response.status, "Removing issuer failed with status: ${response.status}")
        return response.bodyAsText()
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
        println("Response status: ${response.status}")
        println("Response body: ${response.bodyAsText()}")
        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample TMT")
        return response.body<TrustMarkType>()
    }

}

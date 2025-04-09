package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.CreateMetadataPolicy
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuerRequest
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class EntityStatementApiTest {
    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testKeyId: String? = null
    private var testMetadataType: String? = null
    private var testMetadataPolicyType: String? = null
    private var testTrustMarkType: String? = null
    private var testTrustMarkIssuer: String? = null
    private var testReceivedTrustMarkId: String? = null
    private var testReceivedTrustMarkJwt: String? = null

    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

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

        // Generate unique test identifiers with timestamps
        testUsername = "entity-test-${System.currentTimeMillis()}"
        testMetadataType = "openid_provider-${System.currentTimeMillis()}"
        testMetadataPolicyType = "openid_relying_party-${System.currentTimeMillis()}"
        testTrustMarkType = "test-trust-mark-type-${System.currentTimeMillis()}"
        testTrustMarkIssuer = "test-trust-mark-issuer-${System.currentTimeMillis()}"
        testReceivedTrustMarkId = "https://example.com/received-trust-mark-${System.currentTimeMillis()}"
        testReceivedTrustMarkJwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
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

    @Test
    fun `Full entity configuration statement flow test`() = runTest {
        try {
            // Step 1: Create a new account for testing
            val account = createTestAccount()
            assertNotNull(account, "Account creation failed")

            // Step 2: Create a key for this account
            // A key is needed to sign the entity statement
            val key = createKey()
            assertNotNull(key, "Key creation failed")
            testKeyId = key.kid

            // Step 3: Create metadata for this account
            // Metadata will be included in the entity statement
            val metadata = createMetadata()
            assertNotNull(metadata, "Metadata creation failed")

            // Step 4: Create metadata policy for this account
            // Policy is used for Federation operations
            val metadataPolicy = createMetadataPolicy()
            assertNotNull(metadataPolicy, "Metadata policy creation failed")

            // Step 5: Add trust mark types
            val trustMarkType = createTrustMarkType()
            assertNotNull(trustMarkType, "Trust mark type creation failed")

            // Step 6: Add trust mark issuers
            val trustMarkIssuer = createTrustMarkIssuer(trustMarkType.id)
            assertNotNull(trustMarkIssuer, "Trust mark issuer creation failed")

            createTrustMarkIssuer(trustMarkType.id, "https://example-issuer.com")

            // step 7: Add received trust marks
            val receivedTrustMark = createReceivedTrustMark()
            assertNotNull(receivedTrustMark, "Received trust mark creation failed")

            // Step 8: Get the entity configuration statement
            // This retrieves the entity statement with all configured data
            val entityStatement = getEntityStatement()
            assertNotNull(entityStatement, "Entity statement retrieval failed")

            // Step 8: Verify the entity statement contains our data
            // Confirm the key ID is included
            assertTrue(entityStatement.contains(testKeyId!!), "Entity statement does not contain the created key")
            // Confirm the metadata type is included
            assertTrue(
                entityStatement.contains(testMetadataType!!),
                "Entity statement does not contain the created metadata"
            )

            // Confirm the response is a valid EntityConfigurationStatement object
            assertDoesNotThrow { json.decodeFromString(EntityConfigurationStatement.serializer(), entityStatement) }
            println(entityStatement)

            // Confirm the key ID is included (duplicate check)
            assertTrue(entityStatement.contains(testKeyId!!), "Entity statement does not contain the created key")
            // Confirm metadata policy type is included
            assertTrue(
                entityStatement.contains(testMetadataPolicyType!!),
                "Entity statement does not contain the created policy metadata"
            )
            // Confirm trust mark type is included
            assertTrue(
                entityStatement.contains(testTrustMarkType!!),
                "Entity statement does not contain the created trust mark type"
            )
            // Confirm trust mark issuer is included
            assertTrue(
                entityStatement.contains(testTrustMarkIssuer!!),
                "Entity statement does not contain the created trust mark issuer"
            )
            // Confirm received trust mark is included
            assertTrue(
                entityStatement.contains(testReceivedTrustMarkId!!),
                "Entity statement does not contain the created received trust mark"
            )

            // Step 9: Test the publish functionality
            // This would normally publish the statement, but we use dryRun=true in the test
            val publishResult = publishEntityStatement()
            assertNotNull(publishResult, "Entity statement publishing failed")
        } catch (e: Exception) {
            fail("Entity configuration statement test failed: ${e.message}")
        }
    }

    /**
     * Creates a test account with a unique username for use in tests
     *
     * @return The response body as text containing the created account details
     */
    private suspend fun createTestAccount(): String {
        val response = client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateAccount(
                    username = testUsername!!,
                    identifier = "https://test-identifier.com/$testUsername"
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status, "Account creation failed with status: ${response.status}")
        return response.bodyAsText()
    }

    /**
     * Creates a key for the test account
     * Keys are used to sign entity statements and other federation documents
     *
     * @return The created key as an AccountJwk object
     */
    private suspend fun createKey(): AccountJwk {
        val response = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(CreateKey()) // Default parameters will generate a new RSA key
        }

        assertEquals(HttpStatusCode.Created, response.status, "Key creation failed with status: ${response.status}")

        val responseBody = response.bodyAsText()
        println("Created key: $responseBody")

        return json.decodeFromString<AccountJwk>(responseBody)
    }

    /**
     * Creates OpenID Provider metadata for the test account
     * This metadata represents the configuration parameters for an OpenID Provider
     *
     * @return The response body as text containing the created metadata
     */
    private suspend fun createMetadata(): String {
        // Create a JSON object with standard OpenID Federation metadata fields
        val metadataJson = buildJsonObject {
            put("openid_provider", buildJsonObject {
                put("issuer", JsonPrimitive("https://ntnu.no"))
                put("organization_name", JsonPrimitive("NTNU"))
            })
            put("oauth_client", buildJsonObject {
                put("organization_name", JsonPrimitive("NTNU"))
            })
        }

        val response = client.post("$baseUrl/metadata") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadata(
                    key = testMetadataType!!, // This will be openid_provider with a timestamp
                    metadata = metadataJson
                )
            )
        }

        println(response.bodyAsText())

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Metadata creation failed with status: ${response.status}"
        )

        println(response.bodyAsText())
        return response.bodyAsText()
    }

    /**
     * Retrieves the entity statement for the test account
     * The entity statement contains metadata, keys, and other federation information
     *
     * @return The entity statement as a JSON string
     */
    private suspend fun getEntityStatement(): String {
        val response = client.get("$baseUrl/entity-statement") {
            headers {
                append("X-Account-Username", testUsername!!)
            }
        }

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Entity statement retrieval failed with status: ${response.status}"
        )
        return response.bodyAsText()
    }

    /**
     * Tests publishing the entity statement (with dryRun=true)
     * In a real scenario, this would make the statement available at the entity's federation endpoint
     *
     * @return The publishing result as a string
     */
    private suspend fun publishEntityStatement(): String {
        val response = client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                PublishStatementRequest(
                    dryRun = true, // Don't actually publish in test mode
                    kid = testKeyId  // Use the key ID we created earlier
                )
            )
        }

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Entity statement publishing failed with status: ${response.status}"
        )
        return response.bodyAsText()
    }

    /**
     * Creates a metadata policy for the test account
     * Metadata policies are used in federation to constrain subordinate entity metadata
     *
     * @return The response body as text containing the created metadata policy
     */
    private suspend fun createMetadataPolicy(): String {
        println("Creating metadata policy with username: $testUsername")
        println("Using metadata policy type: $testMetadataPolicyType")

        // Create a policy that requires specific response types and issuer pattern
        val policyJson = buildJsonObject {
            put("openid_provider", buildJsonObject {
                put("id_token_signing_alg_values_supported", buildJsonObject {
                    put("subset_of", buildJsonArray {
                        add(JsonPrimitive("RS256"))
                        add(JsonPrimitive("RS384"))
                        add(JsonPrimitive("RS512"))
                    })
                })
                put("op_policy_uri", buildJsonObject {
                    put("regexp", JsonPrimitive("^https://[\\w-]+\\.example\\.com/[\\w-]+\\.html"))
                })
            })
            put("oauth_client", buildJsonObject {
                put("grant_types", buildJsonObject {
                    put("one_of", buildJsonArray {
                        add(JsonPrimitive("authorization_code"))
                        add(JsonPrimitive("client_credentials"))
                    })
                })
            })
        }

        val response = client.post("$baseUrl/metadata-policy") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadataPolicy(
                    key = testMetadataPolicyType!!, // This will be openid_relying_party with a timestamp
                    policy = policyJson
                )
            )
        }

        val responseBody = response.bodyAsText()
        println("Metadata policy HTTP status: ${response.status}")
        println("Metadata policy response: $responseBody")

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Metadata policy creation failed with status: ${response.status}"
        )
        return responseBody
    }

    /**
     * Creates a trust mark type for the test account
     * Trust mark types are used to define the type of trust mark
     *
     * @return The response body as text containing the created trust mark type
     */
    private suspend fun createTrustMarkType(): TrustMarkType {
        val response = client.post("$baseUrl/trust-mark-types") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateTrustMarkType(
                    identifier = testTrustMarkType!!
                )
            )
        }

        val responseBody = response.bodyAsText()
        println("Trust mark type HTTP status: ${response.status}")
        println("Trust mark type response: $responseBody")

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Trust mark type creation failed with status: ${response.status}"
        )
        return json.decodeFromString(TrustMarkType.serializer(), responseBody)
    }

    /**
     * Creates a trust mark issuer for the test account
     * Trust mark issuers are used to define the issuer of a trust mark
     *
     * @return The response body as text containing the created trust mark issuer
     */
    private suspend fun createTrustMarkIssuer(trustMarkTypeId: String, identifier: String? = null): String {
        val response = client.post("$baseUrl/trust-mark-types/${trustMarkTypeId}/issuers") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateTrustMarkTypeIssuerRequest(
                    identifier = identifier ?: testTrustMarkIssuer!!
                )
            )
        }

        val responseBody = response.bodyAsText()
        println("Trust mark issuer HTTP status: ${response.status}")
        println("Trust mark issuer response: $responseBody")

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Trust mark issuer creation failed with status: ${response.status}"
        )
        return responseBody
    }

    /**
     * Creates a received trust mark for the test account
     * Received trust marks are used to define the trust marks received by the account
     *
     * @return The response body as text containing the created received trust mark
     */
    private suspend fun createReceivedTrustMark(): ReceivedTrustMark {
        val response = client.post("$baseUrl/received-trust-marks") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateReceivedTrustMark(
                    trustMarkId = testReceivedTrustMarkId!!,
                    jwt = testReceivedTrustMarkJwt!!
                )
            )
        }

        val responseBody = response.bodyAsText()
        println("Received trust mark HTTP status: ${response.status}")
        println("Received trust mark response: $responseBody")

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Received trust mark creation failed with status: ${response.status}"
        )
        return json.decodeFromString(ReceivedTrustMark.serializer(), responseBody)
    }
}

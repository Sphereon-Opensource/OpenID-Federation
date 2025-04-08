package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.CreateMetadataPolicy
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
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
import kotlinx.serialization.json.putJsonArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class EntityConfigurationStatementTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testKeyId: String? = null
    private var testMetadataType: String? = null
    private var testMetadataPolicyType: String? = null

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
        testUsername = "entity-test-${System.currentTimeMillis()}"
        testMetadataType = "openid_provider-${System.currentTimeMillis()}"
        testMetadataPolicyType = "openid_relying_party-${System.currentTimeMillis()}"
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
            // Step 1: Create a new account
            val account = createTestAccount()
            assertNotNull(account, "Account creation failed")

            // Step 2: Create a key for this account
            val key = createKey()
            assertNotNull(key, "Key creation failed")
            testKeyId = key.kid

            // Step 3: Create metadata for this account
            val metadata = createMetadata()
            assertNotNull(metadata, "Metadata creation failed")

            // Step 4: Create metadata policy for this account
            val metadataPolicy = createMetadataPolicy()
            assertNotNull(metadataPolicy, "Metadata policy creation failed")

            // Step 5: Get the entity configuration statement
            val entityStatement = getEntityStatement()
            assertNotNull(entityStatement, "Entity statement retrieval failed")

            // Step 6: Verify the entity statement contains our data
            assertTrue(entityStatement.contains(testKeyId!!), "Entity statement does not contain the created key")
            assertTrue(
                entityStatement.contains(testMetadataType!!),
                "Entity statement does not contain the created metadata"
            )

            println(entityStatement)
            assertTrue(entityStatement.contains(testKeyId!!), "Entity statement does not contain the created key")
            assertTrue(
                entityStatement.contains(testMetadataPolicyType!!),
                "Entity statement does not contain the created policy metadata"
            )

            val publishResult = publishEntityStatement()
            assertNotNull(publishResult, "Entity statement publishing failed")
        } catch (e: Exception) {
            fail("Entity configuration statement test failed: ${e.message}")
        }
    }

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

    private suspend fun createKey(): AccountJwk {
        val response = client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(CreateKey())
        }

        assertEquals(HttpStatusCode.Created, response.status, "Key creation failed with status: ${response.status}")

        val responseBody = response.bodyAsText()
        println("Created key: $responseBody")

        return json.decodeFromString<AccountJwk>(responseBody)
    }

    private suspend fun createMetadata(): String {
        val metadataJson = buildJsonObject {
            put("issuer", JsonPrimitive("https://test-issuer.com/$testUsername"))
            put("authorization_endpoint", JsonPrimitive("https://test-issuer.com/$testUsername/authorize"))
            put("token_endpoint", JsonPrimitive("https://test-issuer.com/$testUsername/token"))
            put("userinfo_endpoint", JsonPrimitive("https://test-issuer.com/$testUsername/userinfo"))
            put("jwks_uri", JsonPrimitive("https://test-issuer.com/$testUsername/jwks"))
            putJsonArray("response_types_supported") {
                add(JsonPrimitive("code"))
                add(JsonPrimitive("id_token"))
                add(JsonPrimitive("token id_token"))
            }
        }

        val response = client.post("$baseUrl/metadata") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadata(
                    key = testMetadataType!!,
                    metadata = metadataJson
                )
            )
        }

        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "Metadata creation failed with status: ${response.status}"
        )

        println(response.bodyAsText())
        return response.bodyAsText()
    }

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

    private suspend fun publishEntityStatement(): String {
        val response = client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                PublishStatementRequest(
                    dryRun = true,
                    kid = testKeyId
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

    private suspend fun createMetadataPolicy(): String {
        println("Creating metadata policy with username: $testUsername")
        println("Using metadata policy type: $testMetadataPolicyType")

        val policyJson = buildJsonObject {
            put("subset_of", buildJsonObject {
                putJsonArray("response_types_supported") {
                    add(JsonPrimitive("code"))
                    add(JsonPrimitive("id_token"))
                }
            })
            put("value", buildJsonObject {
                put("issuer", JsonPrimitive("https://policy-enforced-issuer.com/*"))
            })
        }

        val response = client.post("$baseUrl/metadata-policy") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadataPolicy(
                    key = testMetadataPolicyType!!,
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
}

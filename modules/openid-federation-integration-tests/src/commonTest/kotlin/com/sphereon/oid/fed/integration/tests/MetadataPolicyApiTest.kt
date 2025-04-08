package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateMetadataPolicy
import com.sphereon.oid.fed.openapi.models.MetadataPolicy
import com.sphereon.oid.fed.openapi.models.MetadataPolicyResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MetadataPolicyApiTest {
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
        testUsername = "m-policy-test-${System.currentTimeMillis()}"

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
                println("Cleanup failed for policy test: ${e.message}")
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
                        identifier = "https://test-policy-identifier.com/$testUsername"
                    )
                )
            }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "Account creation failed with status: ${response.status}"
            )
        } catch (e: Exception) {
            fail("Failed to create test account for policy test: ${e.message}")
        }
    }

    @Test
    fun `POST metadata-policies with valid data should create policy`() = runTest {
        try {
            val policyKey = "openid_relying_party_create"
            val policyJson = buildJsonObject {
                put("scope", buildJsonObject {
                    put("value", JsonPrimitive("openid profile email"))
                })
                put("contacts", buildJsonObject {
                    put("add", JsonPrimitive("info@example.com"))
                })
            }

            val response = client.post("$baseUrl/metadata-policy") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateMetadataPolicy(
                        key = policyKey,
                        policy = policyJson
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val createdPolicy = response.body<MetadataPolicy>()
            assertEquals(policyKey, createdPolicy.key)
            assertEquals(policyJson.toString(), createdPolicy.policy.toString()) // Compare string representations
        } catch (e: Exception) {
            fail("Policy POST request failed: ${e.message}")
        }
    }

    @Test
    fun `GET metadata-policies should return all policies for account`() = runTest {
        try {
            createSamplePolicy()

            val response = client.get("$baseUrl/metadata-policy") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val policies = response.body<MetadataPolicyResponse>()
            assertTrue(policies.metadataPolicies.isNotEmpty(), "Response should contain policies")
            assertTrue(
                policies.metadataPolicies.any { it.key == "openid_relying_party" },
                "Should contain sample policy key"
            )
        } catch (e: Exception) {
            fail("Policy GET request failed: ${e.message}")
        }
    }


    @Test
    fun `DELETE metadata-policies should remove policy entry`() = runTest {
        try {
            // First, create policy to delete
            val policyKey = "policy_to_delete"
            val policyJson = buildJsonObject {
                put("redirect_uris", buildJsonObject {
                    put("add", JsonPrimitive("https://client.example.org/callback"))
                })
            }

            var response = client.post("$baseUrl/metadata-policy") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody(
                    CreateMetadataPolicy(
                        key = policyKey,
                        policy = policyJson
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status, "Failed to create test policy")

            val createdPolicy = response.body<MetadataPolicy>()
            val policyId = createdPolicy.id
            println("Extracted policy ID: $policyId")

            response = client.delete("$baseUrl/metadata-policy/$policyId") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val deletedPolicyResponse = response.body<MetadataPolicy>()
            assertEquals(policyId, deletedPolicyResponse.id)
            assertEquals(policyKey, deletedPolicyResponse.key)

            val getResponse = client.get("$baseUrl/metadata-policy") {
                headers {
                    append("X-Account-Username", testUsername!!)
                }
            }
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val currentPolicies = getResponse.body<MetadataPolicyResponse>().metadataPolicies
            assertTrue(currentPolicies.none { it.id == policyId }, "Deleted policy should not be found")

        } catch (e: Exception) {
            fail("Policy DELETE request failed: ${e.message}")
        }
    }

    @Test
    fun `POST metadata-policies with invalid JSON should fail`() = runTest {
        try {
            val response = client.post("$baseUrl/metadata-policies") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", testUsername!!)
                }
                setBody("{\"key\": \"invalid\", \"metadata\": \"not-a-json-object\"}")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            assertTrue(true, "Request failed as expected for invalid JSON")
        }
    }

    @Test
    fun `POST metadata-policies with non-existent account should fail`() = runTest {
        try {
            val nonExistentUsername = "non-existent-policy-user-${System.currentTimeMillis()}"
            val policyJson = buildJsonObject {
                put("test_policy", JsonPrimitive("value"))
            }

            val response = client.post("$baseUrl/metadata-policies") {
                contentType(ContentType.Application.Json)
                headers {
                    append("X-Account-Username", nonExistentUsername)
                }
                setBody(
                    CreateMetadataPolicy(
                        key = "some_policy",
                        policy = policyJson
                    )
                )
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {
            fail("Non-existent account policy POST request failed: ${e.message}")
        }
    }

    private suspend fun createSamplePolicy() {
        val policyKey = "openid_relying_party"
        val policyJson = buildJsonObject {
            put("application_type", buildJsonObject {
                put("one_of", JsonPrimitive("web native"))
            })
            put("contacts", buildJsonObject {
                put("add", JsonPrimitive("ops@rp.example.com"))
            })
        }

        val response = client.post("$baseUrl/metadata-policy") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", testUsername!!)
            }
            setBody(
                CreateMetadataPolicy(
                    key = policyKey,
                    policy = policyJson
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, "Failed to create sample policy")
    }
}

package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.CreateSubordinateConstraints
import com.sphereon.openid.fed.openapi.models.NamingConstraints
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints
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
import kotlin.test.fail

class SubordinateConstraintApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private var testUsername: String? = null
    private var testSubordinateId: String? = null

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8081"
        client = HttpClient { install(ContentNegotiation) { json(json) } }
        testUsername = "constraint-test-${System.currentTimeMillis()}"

        runTest {
            createTestAccount()
            testSubordinateId = createSubordinate().id
        }
    }

    @AfterTest
    fun tearDown() {
        runTest {
            try {
                if (testUsername != null) {
                    if (testSubordinateId != null) {
                        client.delete("$baseUrl/subordinates/$testSubordinateId") {
                            headers { append("X-Account-Username", testUsername!!) }
                        }
                    }
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

    private suspend fun createTestAccount() {
        val response = client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(CreateAccount(username = testUsername!!, identifier = "https://constraint-test.com/$testUsername"))
        }
        assertEquals(HttpStatusCode.Created, response.status, "Account creation failed: ${response.bodyAsText()}")

        client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            headers { append("X-Account-Username", testUsername!!) }
        }
    }

    private suspend fun createSubordinate(): Subordinate {
        val response = client.post("$baseUrl/subordinates") {
            contentType(ContentType.Application.Json)
            headers { append("X-Account-Username", testUsername!!) }
            setBody(CreateSubordinate(identifier = "https://sub-constraint.example.com/${System.currentTimeMillis()}"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `PUT constraints should set subordinate constraints`() = runTest {
        try {
            val constraints = CreateSubordinateConstraints(
                maxPathLength = 2,
                namingConstraints = NamingConstraints(
                    permitted = listOf("https://*.example.com"),
                    excluded = listOf("https://*.evil.com")
                )
            )

            val response = client.put("$baseUrl/subordinates/$testSubordinateId/constraints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(constraints)
            }

            assertEquals(HttpStatusCode.OK, response.status, "Setting constraints failed: ${response.bodyAsText()}")
            val result = response.body<SubordinateConstraints>()
            assertNotNull(result.constraints)
            assertEquals(2, result.constraints.maxPathLength)
        } catch (e: Exception) {
            fail("PUT constraints failed: ${e.message}")
        }
    }

    @Test
    fun `GET constraints should return subordinate constraints`() = runTest {
        try {
            // Set constraints first
            val constraints = CreateSubordinateConstraints(
                maxPathLength = 3,
                allowedEntityTypes = listOf("openid_provider", "openid_relying_party")
            )
            client.put("$baseUrl/subordinates/$testSubordinateId/constraints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(constraints)
            }

            // Get constraints
            val response = client.get("$baseUrl/subordinates/$testSubordinateId/constraints") {
                headers { append("X-Account-Username", testUsername!!) }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = response.body<SubordinateConstraints>()
            assertEquals(3, result.constraints.maxPathLength)
        } catch (e: Exception) {
            fail("GET constraints failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE constraints should remove subordinate constraints`() = runTest {
        try {
            // Set constraints first
            client.put("$baseUrl/subordinates/$testSubordinateId/constraints") {
                contentType(ContentType.Application.Json)
                headers { append("X-Account-Username", testUsername!!) }
                setBody(CreateSubordinateConstraints(maxPathLength = 1))
            }

            // Delete constraints
            val deleteResponse = client.delete("$baseUrl/subordinates/$testSubordinateId/constraints") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify they're gone
            val getResponse = client.get("$baseUrl/subordinates/$testSubordinateId/constraints") {
                headers { append("X-Account-Username", testUsername!!) }
            }
            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        } catch (e: Exception) {
            fail("DELETE constraints failed: ${e.message}")
        }
    }
}

package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.CreateAccount
import io.ktor.client.*
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

class AccountApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String


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
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `GET accounts should return all accounts`() = runTest {
        try {
            val response = client.get("$baseUrl/accounts")

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("accounts"))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST accounts with valid data should create account`() = runTest {
        try {
            val uniqueUsername = "testuser-${System.currentTimeMillis()}"
            val response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = uniqueUsername,
                        identifier = "https://test-identifier.com/$uniqueUsername"
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains(uniqueUsername))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST accounts with existing username should fail`() = runTest {
        try {
            // First, create an account
            val username = "duplicate-user-${System.currentTimeMillis()}"
            var response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = username,
                        identifier = "https://test-identifier.com/$username"
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)

            // Now try to create another with the same username
            response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = username,
                        identifier = "https://another-identifier.com/$username"
                    )
                )
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.Conflict, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE accounts with valid account should delete account`() = runTest {
        try {
            // First, create an account to delete
            val username = "delete-user-${System.currentTimeMillis()}"
            var response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateAccount(
                        username = username
                    )
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)

            // Now delete that account
            response = client.delete("$baseUrl/accounts") {
                headers {
                    append("X-Account-Username", username)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(username))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE accounts with root account should fail`() = runTest {
        try {
            val response = client.delete("$baseUrl/accounts") {
                headers {
                    append("X-Account-Username", "root")
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Root account cannot be deleted"))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST accounts with invalid body should return client error`() = runTest {
        try {
            val response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody("{\"invalid\": \"json\"}")
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }
}


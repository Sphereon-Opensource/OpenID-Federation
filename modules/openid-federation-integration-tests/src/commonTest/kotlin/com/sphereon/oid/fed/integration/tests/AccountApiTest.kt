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
            // Test retrieving all accounts from the server
            // This verifies the account listing functionality
            val response = client.get("$baseUrl/accounts")

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify successful response code
            assertEquals(HttpStatusCode.OK, response.status)
            // Verify response contains accounts data
            assertTrue(response.bodyAsText().contains("accounts"))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST accounts with valid data should create account`() = runTest {
        try {
            // Create a unique username to avoid conflicts with existing accounts
            val uniqueUsername = "testuser-${System.currentTimeMillis()}"

            // Test account creation with valid data
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

            // Verify created status code for successful account creation
            assertEquals(HttpStatusCode.Created, response.status)
            // Verify response contains the created username
            assertTrue(response.bodyAsText().contains(uniqueUsername))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    // Test for duplicate username handling in the next method

    @Test
    fun `POST accounts with existing username should fail`() = runTest {
        try {
            // First, create an account with a unique username
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

            // Verify first account creation succeeds
            assertEquals(HttpStatusCode.Created, response.status)

            // Now try to create another account with the same username
            // This should fail with a conflict error
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

            // Verify conflict status for duplicate username
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

            // Verify account was created successfully
            assertEquals(HttpStatusCode.Created, response.status)

            // Now delete the account we just created
            response = client.delete("$baseUrl/accounts") {
                headers {
                    append("X-Account-Username", username)
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify successful deletion
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(username))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE accounts with root account should fail`() = runTest {
        try {
            // Attempt to delete the root account, which should be protected
            val response = client.delete("$baseUrl/accounts") {
                headers {
                    append("X-Account-Username", "root")
                }
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify bad request status for protected account
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Root account cannot be deleted"))
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }

    @Test
    fun `POST accounts with invalid body should return client error`() = runTest {
        try {
            // Test account creation with invalid JSON body
            val response = client.post("$baseUrl/accounts") {
                contentType(ContentType.Application.Json)
                setBody("{\"invalid\": \"json\"}")
            }

            println("Status code: ${response.status}")
            println("Response body: ${response.bodyAsText()}")

            // Verify bad request status for invalid input
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } catch (e: Exception) {
            fail("Request failed: ${e.message}")
        }
    }
}


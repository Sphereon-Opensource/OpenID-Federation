package com.sphereon.oid.fed.integration.tests

import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateAccount
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysApiTest {
    companion object {
        private const val ACCOUNT_USERNAME = "test-user-keys-integration"
        private lateinit var setupClient: HttpClient
        lateinit var baseUrl: String

        private val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

        @BeforeAll
        @JvmStatic
        fun setupClass() {
            baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8080"
            setupClient = HttpClient {
                install(ContentNegotiation) {
                    json(jsonConfig)
                }
            }

            println("Attempting to create account '$ACCOUNT_USERNAME' before tests...")
            runBlocking {
                try {
                    val accountToCreate = CreateAccount(
                        identifier = "https://test-account.com",
                        username = ACCOUNT_USERNAME
                    )

                    val response =
                        setupClient.post("$baseUrl/accounts") {
                            contentType(ContentType.Application.Json)
                            setBody(accountToCreate)
                        }

                    if (response.status == HttpStatusCode.Created) {
                        println("Account '$ACCOUNT_USERNAME' created successfully (Status: ${response.status}).")
                    } else if (response.status == HttpStatusCode.Conflict || response.status == HttpStatusCode.BadRequest) {
                        val body = response.bodyAsText()
                        println("Account '$ACCOUNT_USERNAME' might already exist or bad request (Status: ${response.status}). Body: $body. Proceeding with tests.")
                        if (response.status == HttpStatusCode.BadRequest) {
                            fail("Failed to create prerequisite account '$ACCOUNT_USERNAME'. Status: ${response.status}, Body: $body")
                        }
                    } else {
                        val body = response.bodyAsText()
                        fail("Failed to create prerequisite account '$ACCOUNT_USERNAME'. Status: ${response.status}, Body: $body")
                    }
                } catch (e: Exception) {

                    val message = e.message ?: e.javaClass.simpleName
                    val causeMessage = e.cause?.message ?: e.cause?.javaClass?.simpleName
                    fail("Exception during account creation setup for '$ACCOUNT_USERNAME': $message ${causeMessage?.let { "(Cause: $it)" } ?: ""}")
                }
            }
            println("Account setup finished.")
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            println("Attempting to delete account '$ACCOUNT_USERNAME' after tests...")
            runBlocking {
                try {
                    val response = setupClient.delete("$baseUrl/accounts") {

                        headers { append("X-Account-Username", ACCOUNT_USERNAME) }
                    }

                    if (response.status == HttpStatusCode.OK) {
                        println("Account '$ACCOUNT_USERNAME' deleted successfully.")
                    } else {
                        val body = try {
                            response.bodyAsText()
                        } catch (_: Exception) {
                            "(No response body)"
                        }
                        println("Could not delete account '$ACCOUNT_USERNAME' (Status: ${response.status}, Body: $body). It might have already been deleted or setup failed.")
                    }
                } catch (e: Exception) {
                    println("Exception during account deletion teardown for '$ACCOUNT_USERNAME': ${e.message}")
                } finally {
                    setupClient.close()
                    println("Account teardown finished.")
                }
            }
        }
    }

    private lateinit var client: HttpClient

    @BeforeTest
    fun setup() {

        client = HttpClient {
            install(ContentNegotiation) {
                json(jsonConfig)
            }

            defaultRequest {
                url(baseUrl)
                header("X-Account-Username", ACCOUNT_USERNAME)
            }


        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `GET keys should return list of keys for account`() = runTest {
        try {

            val response = client.get("/keys")
            println("GET /keys Status: ${response.status}, Body: ${response.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, response.status)
        } catch (e: Exception) {
            fail("GET /keys failed: ${e.message}")
        }
    }

    @Test
    fun `POST keys with valid data should create a new key`() = runTest {
        try {


            val response = client.post("/keys") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            println("POST /keys Status: ${response.status}, Body: ${response.bodyAsText()}")
            assertEquals(HttpStatusCode.Created, response.status)
        } catch (e: Exception) {
            fail("POST /keys failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE keys with valid keyId should revoke the key`() = runTest {

        val createResponse: HttpResponse = try {
            client.post("/keys") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        } catch (e: Exception) {
            fail("Failed to create key before DELETE test: ${e.message}")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, "Key creation prerequisite failed")


        val keyIdToDelete: String = try {
            val responseText = createResponse.bodyAsText()

            Companion.jsonConfig.decodeFromString<AccountJwk>(responseText).id
        } catch (e: Exception) {
            fail("Failed to parse key ID from creation response: ${e.message}")
        }

        try {

            val deleteResponse = client.delete("/keys/$keyIdToDelete")
            println("DELETE /keys/$keyIdToDelete Status: ${deleteResponse.status}, Body: ${deleteResponse.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)
        } catch (e: Exception) {
            fail("DELETE /keys/$keyIdToDelete failed: ${e.message}")
        }
    }

    @Test
    fun `DELETE keys with non-existent keyId should return not found`() = runTest {

        val keyIdToDelete = "cfd59015-2dff-4f29-87f4-89081e94fac1"
        try {

            val response = client.delete("/keys/$keyIdToDelete")
            println("DELETE /keys/$keyIdToDelete Not Found Status: ${response.status}, Body: ${response.bodyAsText()}")
            assertEquals(HttpStatusCode.NotFound, response.status)
        } catch (e: Exception) {

            fail("DELETE /keys/$keyIdToDelete Not Found test failed unexpectedly: ${e.message}")
        }
    }
}

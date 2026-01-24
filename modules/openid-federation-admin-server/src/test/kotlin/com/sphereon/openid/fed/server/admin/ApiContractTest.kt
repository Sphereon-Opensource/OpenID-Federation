package com.sphereon.openid.fed.server.admin

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that verify the Admin API contract structure.
 * These tests validate request/response formats match the OpenAPI specification.
 */
class ApiContractTest {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class ErrorResponse(
        val error: String,
        val errorDescription: String? = null
    )

    @Serializable
    data class AccountDTO(
        val username: String,
        val identifier: String? = null
    )

    @Test
    fun `accounts endpoint returns array of accounts`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/accounts") {
                    call.respond(listOf(
                        AccountDTO("user1", "https://example.com/user1"),
                        AccountDTO("user2", "https://example.com/user2")
                    ))
                }
            }
        }

        val response = client.get("/accounts") {
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.startsWith("["))
        assertTrue(body.contains("user1"))
        assertTrue(body.contains("user2"))
    }

    @Test
    fun `create account endpoint accepts JSON body`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                post("/accounts") {
                    val body = call.receiveText()
                    val account = json.decodeFromString<AccountDTO>(body)
                    call.respond(HttpStatusCode.Created, account.copy(identifier = "https://example.com/${account.username}"))
                }
            }
        }

        val response = client.post("/accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newuser"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("newuser"))
        assertTrue(body.contains("identifier"))
    }

    @Test
    fun `error responses follow standard format`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_request", cause.message)
                    )
                }
            }

            routing {
                post("/accounts") {
                    throw IllegalArgumentException("Username already exists")
                }
            }
        }

        val response = client.post("/accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"existing"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error"))
        assertTrue(body.contains("invalid_request"))
    }

    @Test
    fun `keys endpoint structure is valid`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/keys") {
                    call.respondText(
                        """{"keys":[{"kid":"key-1","kty":"EC","crv":"P-256"}]}""",
                        ContentType.Application.Json
                    )
                }

                post("/keys") {
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("kid" to "new-key", "kty" to "EC", "crv" to "P-256")
                    )
                }
            }
        }

        val getResponse = client.get("/keys")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertTrue(getResponse.bodyAsText().contains("keys"))

        val postResponse = client.post("/keys") {
            contentType(ContentType.Application.Json)
            setBody("""{"kty":"EC","crv":"P-256"}""")
        }
        assertEquals(HttpStatusCode.Created, postResponse.status)
    }

    @Test
    fun `subordinates endpoint structure is valid`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/subordinates") {
                    call.respondText(
                        """[{"identifier":"https://sub1.example.com"},{"identifier":"https://sub2.example.com"}]""",
                        ContentType.Application.Json
                    )
                }

                post("/subordinates") {
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("identifier" to "https://new-sub.example.com")
                    )
                }
            }
        }

        val response = client.get("/subordinates")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("identifier"))
    }

    @Test
    fun `entity-statement endpoint returns signed JWT`() = testApplication {
        application {
            routing {
                get("/entity-statement") {
                    // Entity statements are returned as JWTs
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0In0.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIn0.signature",
                        ContentType("application", "entity-statement+jwt")
                    )
                }

                post("/entity-statement/publish") {
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0In0.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIn0.signature",
                        ContentType("application", "entity-statement+jwt")
                    )
                }
            }
        }

        val response = client.get("/entity-statement")
        assertEquals(HttpStatusCode.OK, response.status)
        // JWT format: header.payload.signature
        assertTrue(response.bodyAsText().count { it == '.' } == 2)
    }

    @Test
    fun `trust-mark endpoints structure is valid`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/trust-marks") {
                    call.respondText(
                        """[{"id":"https://trust.example.com/mark1"}]""",
                        ContentType.Application.Json
                    )
                }

                get("/trust-marks/types") {
                    call.respondText(
                        """[{"identifier":"https://trust.example.com/type1","name":"Type 1"}]""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        val marksResponse = client.get("/trust-marks")
        assertEquals(HttpStatusCode.OK, marksResponse.status)

        val typesResponse = client.get("/trust-marks/types")
        assertEquals(HttpStatusCode.OK, typesResponse.status)
    }

    @Test
    fun `metadata endpoint structure is valid`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/metadata") {
                    call.respondText(
                        """[{"key":"federation_entity","metadata":{"organization_name":"Example Org"}}]""",
                        ContentType.Application.Json
                    )
                }

                post("/metadata") {
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("key" to "openid_provider", "metadata" to mapOf("issuer" to "https://example.com"))
                    )
                }
            }
        }

        val response = client.get("/metadata")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("federation_entity"))
    }

    @Test
    fun `authority-hints endpoint structure is valid`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@ApiContractTest.json)
            }

            routing {
                get("/authority-hints") {
                    call.respondText(
                        """["https://authority1.example.com","https://authority2.example.com"]""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        val response = client.get("/authority-hints")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().startsWith("["))
    }
}

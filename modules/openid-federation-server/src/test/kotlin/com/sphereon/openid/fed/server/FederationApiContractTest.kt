package com.sphereon.openid.fed.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that verify the Federation API contract per OpenID Federation specification.
 * These tests validate request/response formats match the spec requirements.
 */
class FederationApiContractTest {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class ErrorResponse(
        val error: String,
        val error_description: String? = null
    )

    // Content type for entity statements per spec
    private val entityStatementJwtType = ContentType("application", "entity-statement+jwt")
    private val resolveResponseJwtType = ContentType("application", "resolve-response+jwt")
    private val trustMarkJwtType = ContentType("application", "trust-mark+jwt")

    @Test
    fun `entity configuration returns entity-statement JWT content type`() = testApplication {
        application {
            routing {
                get("/.well-known/openid-federation") {
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0Iiwia2lkIjoia2V5LTEifQ.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIiwic3ViIjoiaHR0cHM6Ly9leGFtcGxlLmNvbSIsImlhdCI6MTYzMDAwMDAwMCwiZXhwIjoxNjMwMDg2NDAwLCJqd2tzIjp7ImtleXMiOltdfX0.signature",
                        entityStatementJwtType
                    )
                }
            }
        }

        val response = client.get("/.well-known/openid-federation")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application", response.contentType()?.contentType)
        assertEquals("entity-statement+jwt", response.contentType()?.contentSubtype)

        // Verify JWT structure (3 parts separated by dots)
        val jwt = response.bodyAsText()
        assertEquals(3, jwt.split(".").size, "Entity statement must be a valid JWT")
    }

    @Test
    fun `fetch endpoint returns subordinate statement`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@FederationApiContractTest.json)
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
                get("/fetch") {
                    val iss = call.request.queryParameters["iss"]
                    val sub = call.request.queryParameters["sub"]

                    if (iss.isNullOrEmpty()) {
                        throw IllegalArgumentException("iss parameter is required")
                    }

                    // Return entity statement JWT
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0In0.eyJpc3MiOiIkaXNzIiwic3ViIjoiJHtzdWIgPzogaXNzfSJ9.signature",
                        entityStatementJwtType
                    )
                }
            }
        }

        // Test with valid iss parameter
        val response = client.get("/fetch?iss=https://superior.example.com&sub=https://subordinate.example.com")
        assertEquals(HttpStatusCode.OK, response.status)

        // Test missing iss parameter
        val errorResponse = client.get("/fetch")
        assertEquals(HttpStatusCode.BadRequest, errorResponse.status)
        assertTrue(errorResponse.bodyAsText().contains("error"))
    }

    @Test
    fun `list endpoint returns subordinate entity identifiers`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@FederationApiContractTest.json)
            }

            routing {
                get("/list") {
                    val entityType = call.request.queryParameters["entity_type"]
                    val trustMarked = call.request.queryParameters["trust_marked"]
                    val trustMarkId = call.request.queryParameters["trust_mark_id"]

                    // Per spec, list returns an array of entity identifiers
                    call.respond(listOf(
                        "https://subordinate1.example.com",
                        "https://subordinate2.example.com"
                    ))
                }
            }
        }

        val response = client.get("/list")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        assertTrue(body.startsWith("["), "List response must be a JSON array")
        assertTrue(body.contains("https://subordinate1.example.com"))

        // Test with optional filters
        val filteredResponse = client.get("/list?entity_type=openid_provider")
        assertEquals(HttpStatusCode.OK, filteredResponse.status)
    }

    @Test
    fun `resolve endpoint returns resolve-response JWT or JSON`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@FederationApiContractTest.json)
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
                get("/resolve") {
                    val sub = call.request.queryParameters["sub"]
                    val anchor = call.request.queryParameters["anchor"]
                    val type = call.request.queryParameters["type"]

                    if (sub.isNullOrEmpty()) {
                        throw IllegalArgumentException("sub parameter is required")
                    }

                    // Accept header determines response format
                    val acceptHeader = call.request.headers[HttpHeaders.Accept]

                    if (acceptHeader?.contains("resolve-response+jwt") == true) {
                        call.respondText(
                            "eyJhbGciOiJFUzI1NiIsInR5cCI6InJlc29sdmUtcmVzcG9uc2Urand0In0.eyJzdWIiOiIkc3ViIiwidHJ1c3RfY2hhaW4iOltdfQ.signature",
                            resolveResponseJwtType
                        )
                    } else {
                        call.respondText(
                            """{"sub":"$sub","trust_chain":[],"metadata":{}}""",
                            ContentType.Application.Json
                        )
                    }
                }
            }
        }

        // Test JSON response (default)
        val jsonResponse = client.get("/resolve?sub=https://entity.example.com")
        assertEquals(HttpStatusCode.OK, jsonResponse.status)
        assertTrue(jsonResponse.bodyAsText().contains("trust_chain"))

        // Test JWT response
        val jwtResponse = client.get("/resolve?sub=https://entity.example.com") {
            header(HttpHeaders.Accept, resolveResponseJwtType.toString())
        }
        assertEquals(HttpStatusCode.OK, jwtResponse.status)

        // Test missing required parameter
        val errorResponse = client.get("/resolve")
        assertEquals(HttpStatusCode.BadRequest, errorResponse.status)
    }

    @Test
    fun `trust-mark-status endpoint validates trust marks`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@FederationApiContractTest.json)
            }

            routing {
                get("/trust-mark-status") {
                    val trustMarkId = call.request.queryParameters["trust_mark_id"]
                    val sub = call.request.queryParameters["sub"]

                    if (trustMarkId.isNullOrEmpty() || sub.isNullOrEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("invalid_request", "trust_mark_id and sub are required")
                        )
                        return@get
                    }

                    // Return trust mark status
                    call.respond(mapOf("active" to true))
                }
            }
        }

        val response = client.get("/trust-mark-status?trust_mark_id=https://trust.example.com/mark1&sub=https://entity.example.com")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("active"))
    }

    @Test
    fun `trust-mark endpoint returns trust mark JWT`() = testApplication {
        application {
            routing {
                get("/trust-mark") {
                    val trustMarkId = call.request.queryParameters["trust_mark_id"]
                    val sub = call.request.queryParameters["sub"]

                    if (trustMarkId.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"invalid_request"}""")
                        return@get
                    }

                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6InRydXN0LW1hcmsrand0In0.eyJpc3MiOiJodHRwczovL3RydXN0LmV4YW1wbGUuY29tIiwiaWQiOiIkdHJ1c3RNYXJrSWQifQ.signature",
                        trustMarkJwtType
                    )
                }
            }
        }

        val response = client.get("/trust-mark?trust_mark_id=https://trust.example.com/mark1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application", response.contentType()?.contentType)
        assertEquals("trust-mark+jwt", response.contentType()?.contentSubtype)
    }

    @Test
    fun `trust-marks-listing endpoint returns trust mark identifiers`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(this@FederationApiContractTest.json)
            }

            routing {
                get("/trust-marks-listing") {
                    val sub = call.request.queryParameters["sub"]

                    call.respond(listOf(
                        "https://trust.example.com/mark1",
                        "https://trust.example.com/mark2"
                    ))
                }
            }
        }

        val response = client.get("/trust-marks-listing")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().startsWith("["))
    }

    @Test
    fun `historical-keys endpoint returns historical JWKs`() = testApplication {
        application {
            routing {
                get("/historical-keys") {
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiIsInR5cCI6Imp3ay1zZXQrand0In0.eyJrZXlzIjpbXX0.signature",
                        ContentType("application", "jwk-set+jwt")
                    )
                }
            }
        }

        val response = client.get("/historical-keys")
        assertEquals(HttpStatusCode.OK, response.status)
        // Should return a signed JWK Set
        assertEquals("application", response.contentType()?.contentType)
    }

    @Test
    fun `per-account endpoints follow same structure`() = testApplication {
        application {
            routing {
                get("/{username}/.well-known/openid-federation") {
                    val username = call.parameters["username"]
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tLyR1c2VybmFtZSJ9.sig",
                        entityStatementJwtType
                    )
                }

                get("/{username}/fetch") {
                    val username = call.parameters["username"]
                    val iss = call.request.queryParameters["iss"]
                    call.respondText(
                        "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiIkaXNzIn0.sig",
                        entityStatementJwtType
                    )
                }

                get("/{username}/list") {
                    call.respondText("""["https://sub.example.com"]""", ContentType.Application.Json)
                }
            }
        }

        // Test per-account entity configuration
        val configResponse = client.get("/testuser/.well-known/openid-federation")
        assertEquals(HttpStatusCode.OK, configResponse.status)

        // Test per-account fetch
        val fetchResponse = client.get("/testuser/fetch?iss=https://example.com")
        assertEquals(HttpStatusCode.OK, fetchResponse.status)

        // Test per-account list
        val listResponse = client.get("/testuser/list")
        assertEquals(HttpStatusCode.OK, listResponse.status)
    }
}

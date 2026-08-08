package com.sphereon.openid.fed.server.federation.ktor

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Federation Server health and basic endpoints.
 * These tests verify the Ktor infrastructure works correctly.
 */
class HealthEndpointTest {

    @Test
    fun `health endpoint returns OK`() = testApplication {
        application {
            routing {
                get("/health") {
                    call.respondText("OK")
                }
            }
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `content negotiation is configured for JSON`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            routing {
                get("/api/test") {
                    call.respond(mapOf("status" to "ok", "service" to "federation"))
                }
            }
        }

        val response = client.get("/api/test") {
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("federation"))
        assertTrue(body.contains("status"))
    }

    @Test
    fun `well-known path structure is valid`() = testApplication {
        application {
            routing {
                get("/.well-known/openid-federation") {
                    // Simulated entity configuration response
                    call.respondText(
                        """{"iss":"https://example.com","sub":"https://example.com"}""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        val response = client.get("/.well-known/openid-federation")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("iss"))
        assertTrue(body.contains("sub"))
    }

    @Test
    fun `per-account well-known path is valid`() = testApplication {
        application {
            routing {
                get("/{username}/.well-known/openid-federation") {
                    val username = call.parameters["username"]
                    call.respondText(
                        """{"iss":"https://example.com/$username","sub":"https://example.com/$username"}""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        val response = client.get("/testuser/.well-known/openid-federation")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("testuser"))
    }

    @Test
    fun `resolve endpoint accepts required parameters`() = testApplication {
        application {
            routing {
                get("/resolve") {
                    val sub = call.request.queryParameters["sub"]
                    val anchor = call.request.queryParameters["anchor"]

                    if (sub.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"missing_parameter","error_description":"sub parameter is required"}""")
                        return@get
                    }

                    call.respondText(
                        """{"sub":"$sub","anchor":"$anchor","trust_chain":[]}""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        // Test with valid parameters
        val validResponse = client.get("/resolve?sub=https://entity.example.com&anchor=https://trust.example.com")
        assertEquals(HttpStatusCode.OK, validResponse.status)

        // Test without required parameter
        val invalidResponse = client.get("/resolve")
        assertEquals(HttpStatusCode.BadRequest, invalidResponse.status)
    }

    @Test
    fun `fetch endpoint with iss parameter`() = testApplication {
        application {
            routing {
                get("/fetch") {
                    val iss = call.request.queryParameters["iss"]
                    val sub = call.request.queryParameters["sub"]

                    if (iss.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, """{"error":"missing_parameter"}""")
                        return@get
                    }

                    // Fetch returns a JWT (simulated)
                    call.respondText(
                        "eyJhbGciOiJSUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0In0.eyJpc3MiOiIkaXNzIn0.signature",
                        ContentType("application", "entity-statement+jwt")
                    )
                }
            }
        }

        val response = client.get("/fetch?iss=https://superior.example.com&sub=https://subordinate.example.com")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.contentType == "application")
    }

    @Test
    fun `list endpoint returns subordinate entities`() = testApplication {
        application {
            routing {
                get("/list") {
                    call.respondText(
                        """["https://sub1.example.com","https://sub2.example.com"]""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        val response = client.get("/list")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("sub1.example.com"))
        assertTrue(body.contains("sub2.example.com"))
    }
}

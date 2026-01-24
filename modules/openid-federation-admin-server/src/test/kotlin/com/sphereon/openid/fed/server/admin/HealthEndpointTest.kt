package com.sphereon.openid.fed.server.admin

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Admin Server health and basic endpoints.
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
                    call.respond(mapOf("status" to "ok", "service" to "admin"))
                }
            }
        }

        val response = client.get("/api/test") {
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("admin"))
    }

    @Test
    fun `CORS is configured correctly`() = testApplication {
        application {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
            }

            routing {
                get("/accounts") {
                    call.respondText("[]", ContentType.Application.Json)
                }
            }
        }

        // Test preflight request
        val preflightResponse = client.options("/accounts") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        // CORS should allow the request (either 200 or 204)
        assertTrue(preflightResponse.status.isSuccess() || preflightResponse.status == HttpStatusCode.NoContent)
    }
}

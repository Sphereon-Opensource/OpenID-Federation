package com.sphereon.openid.fed.integration.tests

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared admin base URL + Bearer for integration tests.
 *
 * Admin is always authenticated. JVM actuals mint tokens via in-process IDK AS
 * and boot in-process ACCOUNT admin (+ federation public server) when URLs are unset.
 */
expect fun adminTestBaseUrl(): String

/** Federation public server base URL (protocol endpoints; usually no Bearer). */
expect fun federationTestBaseUrl(): String

/** Valid access token accepted by the admin under test. */
expect fun adminTestBearerToken(): String

val adminTestJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

/**
 * HttpClient that attaches `Authorization: Bearer …` on every request.
 * Still set `X-Account-Username` per call for ACCOUNT entity selection (rebind).
 *
 * @param configure optional extra client config (additional defaultRequest headers, etc.)
 */
fun createAuthenticatedAdminClient(
    json: Json = adminTestJson,
    configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer ${adminTestBearerToken()}")
        }
        configure()
    }

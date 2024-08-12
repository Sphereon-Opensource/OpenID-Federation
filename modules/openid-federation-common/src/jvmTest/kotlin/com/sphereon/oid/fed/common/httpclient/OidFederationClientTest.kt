package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.openapi.models.JWKS
import com.sphereon.oid.fed.openapi.models.JwkDTO
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OidFederationClientTest {

    private val subordinateStatement = SubordinateStatement(
        iss = "https://edugain.org/federation",
        sub = "https://openid.sunet.se",
        exp = 1568397247,
        iat = 1568310847,
        jwks = JWKS(
            propertyKeys = listOf(
                JwkDTO(
                    kid = "dEEtRjlzY3djcENuT01wOGxrZlkxb3RIQVJlMTY0...",
                    kty = "RSA",
                )
            )
        )
    )

    private val mockEngine = MockEngine {
        respond(
            content = Json.encodeToString(subordinateStatement),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
        )
    }

    @Test
    fun testGetEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val response = client.fetchSubordinateStatement(
                iss = "https://edugain.org/federation",
                sub = "https://openid.sunet.se",
                fetchUrl = "https://edugain.org/federation/fetch",
                httpMethod = HttpMethod.Get
            )
            assertEquals(subordinateStatement, response)
        }
    }

    @Test
    fun testPostEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val response = client.fetchSubordinateStatement(
                iss = "https://edugain.org/federation",
                sub = "https://openid.sunet.se",
                fetchUrl = "https://edugain.org/federation/fetch",
                httpMethod = HttpMethod.Post
            )
            assertEquals(subordinateStatement, response)
        }
    }
}

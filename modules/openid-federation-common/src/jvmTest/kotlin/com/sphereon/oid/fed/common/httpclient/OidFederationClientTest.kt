package com.sphereon.oid.fed.common.httpclient

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sphereon.oid.fed.openapi.models.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test

class OidFederationClientTest {

    private val entityStatement = EntityStatement(
            iss = "https://edugain.org/federation",
            sub = "https://openid.sunet.se",
            exp = 1568397247,
            iat = 1568310847,
            sourceEndpoint = "https://edugain.org/federation/federation_fetch_endpoint",
            jwks = JWKS(
                propertyKeys = listOf(
                    JWK(
                        // missing e and n ?
                        kid = "dEEtRjlzY3djcENuT01wOGxrZlkxb3RIQVJlMTY0...",
                        kty = "RSA"
                    )
                )
            ),
            metadata = Metadata(
                federationEntity = FederationEntityMetadata(
                    organizationName = "SUNET"
                )
            )
    )

    private val mockEngine = MockEngine {
        respond(
            content = Json.encodeToString(entityStatement),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
        )
    }

    @Test
    fun testGetEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val response = client.fetchEntityStatement("https://www.example.com?iss=https://edugain.org/federation&sub=https://openid.sunet.se", HttpMethod.Get)
            assert(response == entityStatement)
        }
    }

    @Test
    fun testPostEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val key = RSAKeyGenerator(2048).keyID("key1").generate()
            val entityStatement = EntityStatement(iss = "https://edugain.org/federation", sub = "https://openid.sunet.se")
            val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
            val response = client.fetchEntityStatement("https://www.example.com", HttpMethod.Post,
                OidFederationClient.PostEntityParameters(
                    payload = payload,
                    header = JWTHeader(typ = "JWT", alg = "RS256", kid = key.keyID),
                    key = key.toString(), privateKey = key.toRSAPrivateKey().toString()
                )
            )
            assert(response == entityStatement)
        }
    }
}

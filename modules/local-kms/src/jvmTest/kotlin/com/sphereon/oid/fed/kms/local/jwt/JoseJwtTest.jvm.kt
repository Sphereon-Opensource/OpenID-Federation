package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sphereon.oid.fed.openapi.models.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertTrue

class JoseJwtTest {

    @Test
    fun signTest() {
        val key = ECKeyGenerator(Curve.P_256).keyID("key1").algorithm(Algorithm("ES256")).generate()
        val jwk = key.toString()
        val entityStatement = EntityConfigurationStatement(
            iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = BaseStatementJwks()
        )
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val signature = sign(
            payload,
            JwtHeader(alg = JWSAlgorithm.ES256.toString(), typ = "JWT", kid = key.keyID),
            Json.decodeFromString<JwkWithPrivateKey>(jwk)
        )
        assertTrue { signature.startsWith("ey") }
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun verifyTest() {
        val key = ECKeyGenerator(Curve.P_256).keyID("key1").algorithm(Algorithm("ES256")).generate()
        val jwk = key.toString()
        val entityStatement = EntityConfigurationStatement(
            iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = BaseStatementJwks()
        )
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val signature = sign(
            payload,
            JwtHeader(alg = JWSAlgorithm.ES256.toString(), typ = "JWT", kid = key.keyID),
            Json.decodeFromString<JwkWithPrivateKey>(jwk)
        )
        assertTrue {
            verify(signature, json.decodeFromString<Jwk>(jwk))
        }
    }
}

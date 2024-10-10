package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sphereon.oid.fed.openapi.models.BaseEntityStatementJwks
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
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
            iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = BaseEntityStatementJwks()
        )
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val signature = sign(
            payload,
            JWTHeader(alg = JWSAlgorithm.ES256.toString(), typ = "JWT", kid = key.keyID),
            Json.decodeFromString<Jwk>(jwk)
        )
        assertTrue { signature.startsWith("ey") }
    }

    @Test
    fun verifyTest() {
        val key = ECKeyGenerator(Curve.P_256).keyID("key1").algorithm(Algorithm("ES256")).generate()
        val jwk = key.toString()
        val entityStatement = EntityConfigurationStatement(
            iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = BaseEntityStatementJwks()
        )
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val signature = sign(
            payload,
            JWTHeader(alg = JWSAlgorithm.ES256.toString(), typ = "JWT", kid = key.keyID),
            Json.decodeFromString<Jwk>(jwk)
        )
        assertTrue { verify(signature, Json.decodeFromString<Jwk>(jwk)) }
    }
}

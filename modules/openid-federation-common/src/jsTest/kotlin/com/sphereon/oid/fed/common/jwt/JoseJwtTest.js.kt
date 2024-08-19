package com.sphereon.oid.fed.common.jwt

import com.sphereon.oid.fed.common.jwt.Jose.generateKeyPair
import com.sphereon.oid.fed.openapi.models.EntityStatement
import com.sphereon.oid.fed.openapi.models.JWKS
import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

class JoseJwtTest {
    @OptIn(ExperimentalJsExport::class)
    @Test
    fun signTest() = runTest {
        val keyPair = (generateKeyPair("RS256") as Promise<dynamic>).await()
        val entityStatement = EntityStatement(iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = JWKS())
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val result = async {
            sign(
                payload,
                JWTHeader(typ = "JWT", alg = "RS256", kid = "test"),
                mutableMapOf("privateKey" to keyPair.privateKey)
            )
        }
        assertTrue((result.await() as Promise<String>).await().startsWith("ey"))
    }

    @OptIn(ExperimentalJsExport::class)
    @Test
    fun verifyTest() = runTest {
        val keyPair = (generateKeyPair("RS256") as Promise<dynamic>).await()
        val entityStatement = EntityStatement(iss = "test", sub = "test", exp = 111111, iat = 111111, jwks = JWKS())
        val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
        val signed = (sign(
            payload,
            JWTHeader(typ = "JWT", alg = "RS256", kid = "test"),
            mutableMapOf("privateKey" to keyPair.privateKey)
        ) as Promise<dynamic>).await()
        val result = async { verify(signed, keyPair.publicKey, emptyMap()) }
        assertTrue((result.await()))
    }
}

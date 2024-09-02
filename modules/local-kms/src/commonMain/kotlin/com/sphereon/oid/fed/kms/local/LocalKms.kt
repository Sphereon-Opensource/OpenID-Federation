package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.kms.local.database.LocalKmsDatabase
import com.sphereon.oid.fed.kms.local.jwk.generateKeyPair
import com.sphereon.oid.fed.kms.local.jwt.sign
import com.sphereon.oid.fed.kms.local.jwt.verify
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.*

class LocalKms {

    private val database: LocalKmsDatabase = LocalKmsDatabase()

    fun generateKey(keyId: String) {
        val jwk = generateKeyPair()
        database.insertKey(keyId = keyId, privateKey = jwk.toString())
    }

    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        val jwk = database.getKey(keyId)
        val jwkString: String = Json.decodeFromString(jwk.private_key)
        val jwkObject: Jwk = Json.decodeFromString(jwkString)

        // Adding necessary parameter is header
        val mHeader = header.copy(alg = jwkObject.alg, kid = jwkObject.kid)

        // Adding JWKs object in payload
        val mutablePayload = payload.toMutableMap()
        mutablePayload["kid"] = JsonPrimitive(jwkObject.kid)
        val keyArrayOfJwks = buildJsonObject {
            putJsonArray("keys") {
                addJsonObject {
                    put("kty", jwkObject.kty)
                    put("n", jwkObject.n)
                    put("e", jwkObject.e)
                    put("kid", jwkObject.kid)
                    put("use", jwkObject.use)
                }
            }
        }
        mutablePayload["jwks"] = keyArrayOfJwks
        val mPayload = JsonObject(mutablePayload)

        return sign(header = mHeader, payload = mPayload, key = jwkObject)
    }

    fun verify(token: String, jwk: Jwk): Boolean {
        return verify(jwt = token, key = jwk)
    }
}
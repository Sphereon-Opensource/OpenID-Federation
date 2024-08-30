package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.kms.local.database.LocalKmsDatabase
import com.sphereon.oid.fed.kms.local.jwk.generateKeyPair
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.kms.local.jwt.sign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class LocalKms {

    private val database: LocalKmsDatabase = LocalKmsDatabase()

    fun generateKey(keyId: String) {
        val jwk = generateKeyPair()
        database.insertKey(keyId = keyId, privateKey = jwk.toString())
    }

    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        val jwk = database.getKey(keyId)

        return sign(header = header, payload = payload, key = Json.decodeFromString(jwk.private_key))
    }

    fun verify(token: String, keyId: String): Boolean {
        TODO("Pending")
    }
}
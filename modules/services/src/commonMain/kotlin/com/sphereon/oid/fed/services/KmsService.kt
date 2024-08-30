package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.json.JsonObject

class KmsService(private val provider: String) {

    private val kmsClient: KmsClient = when (provider) {
        "local" -> LocalKmsClient()
        else -> throw IllegalArgumentException("Unsupported KMS provider: $provider")
    }

    fun generateKeyPair(keyId: String) {
        kmsClient.generateKeyPair(keyId)
    }

    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        return kmsClient.sign(header, payload, keyId)
    }

    fun verify(token: String, keyId: String): Boolean {
        return kmsClient.verify(token, keyId)
    }
}

interface KmsClient {
    fun generateKeyPair(keyId: String)
    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String
    fun verify(token: String, keyId: String): Boolean
}
package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.Jwk


class KmsService(private val provider: String) {

    private val kmsClient: KmsClient = when (provider) {
        "local" -> LocalKmsClient()
        else -> throw IllegalArgumentException("Unsupported KMS provider: $provider")
    }

    fun generateKeyPair(keyId: String): Jwk {
        return kmsClient.generateKeyPair(keyId)
    }

    fun sign(data: String, keyId: String): String {
        return kmsClient.sign(data, keyId)
    }

    fun verify(token: String, keyId: String): Boolean {
        return kmsClient.verify(token, keyId)
    }
}

interface KmsClient {
    fun generateKeyPair(keyId: String): Jwk
    fun sign(data: String, keyId: String): String
    fun verify(token: String, keyId: String): Boolean
}
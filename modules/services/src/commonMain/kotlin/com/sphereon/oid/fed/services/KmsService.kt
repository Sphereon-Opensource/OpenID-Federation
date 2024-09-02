package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import kotlinx.serialization.json.JsonObject

object KmsService {
    private val provider: String = System.getenv("KMS_PROVIDER") ?: "local"

    private val kmsClient: KmsClient = when (provider) {
        "local" -> LocalKmsClient()
        else -> throw IllegalArgumentException("Unsupported KMS provider: $provider")
    }

    fun getKmsClient(): KmsClient = kmsClient
}

interface KmsClient {
    fun generateKeyPair(): JwkAdminDTO
    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String
    fun verify(token: String, jwk: Jwk): Boolean
}

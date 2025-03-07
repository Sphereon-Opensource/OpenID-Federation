package com.sphereon.oid.fed.services

import com.sphereon.crypto.KeyInfo
import com.sphereon.crypto.jose.Jwk
import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * Service for handling JWT creation, signing, and validation operations.
 * This centralizes all JWT operations to avoid redundancy across services.
 */
class JwtService(private val kmsProvider: IKeyManagementSystem) {
    private val logger = Logger.tag("JwtService")

    /**
     * Signs a payload with the specified key ID and returns a JWT
     *
     * @param payload The payload to sign
     * @param header The JWT header
     * @param keyId The ID of the key to use for signing
     * @return The signed JWT
     */
    suspend fun sign(payload: JsonObject, header: JwtHeader, keyId: String): String {
        logger.debug("Signing payload with key: $keyId")

        val headerJson = Json.encodeToJsonElement(JwtHeader.serializer(), header).jsonObject.toString()
        val payloadJson = payload.toString()

        // Create properly formatted base64url-encoded segments
        val headerB64 = base64UrlEncode(headerJson.toByteArray())
        val payloadB64 = base64UrlEncode(payloadJson.toByteArray())
        val signingInput = "$headerB64.$payloadB64"

        val keyInfo = KeyInfo<Jwk>(
            kid = keyId,
            kmsKeyRef = keyId
        )

        // Sign the properly formatted message
        val signature = kmsProvider.createRawSignatureAsync(
            keyInfo,
            signingInput.toByteArray(),
            false
        )

        val signatureB64 = base64UrlEncode(signature)
        val jwt = "$headerB64.$payloadB64.$signatureB64"

        logger.debug("Successfully signed JWT with key: $keyId")
        return jwt
    }

    /**
     * Signs a serializable payload object with the specified key ID and returns a JWT
     *
     * @param payload The payload object to serialize and sign
     * @param header The JWT header
     * @param keyId The ID of the key to use for signing
     * @return The signed JWT
     */
    suspend inline fun <reified T> signSerializable(payload: T, header: JwtHeader, keyId: String): String {
        val payloadJson = Json.encodeToJsonElement(Json.serializersModule.serializer(), payload).jsonObject
        return sign(payloadJson, header, keyId)
    }

    /**
     * Utility function to perform base64url encoding
     */
    private fun base64UrlEncode(input: ByteArray): String {
        val base64 = java.util.Base64.getEncoder().encodeToString(input)
        return base64.replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }
}

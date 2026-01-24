package com.sphereon.openid.fed.services

import com.sphereon.crypto.jose.jws.CreateJwsArgs
import com.sphereon.crypto.jose.jws.CreateJwsOpts
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwsIdentifierMode
import com.sphereon.crypto.resolution.managed.ManagedOptsAlias
import com.sphereon.crypto.resolution.managed.ManagedOptsKid
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.JwtCreationFailedError
import com.sphereon.openid.fed.core.error.toErr
import com.sphereon.openid.fed.core.error.toOk
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * Converts an OpenAPI JwtHeader to a JsonObject for use with IDK's CreateJwsOpts.
 */
fun JwtHeader.toJsonObject(): JsonObject {
    return Json.encodeToJsonElement(JwtHeader.serializer(), this).jsonObject
}

/**
 * Signs a serializable payload using IDK's JwtService.
 *
 * @param payload The payload object to serialize and sign
 * @param header The JWT header
 * @param kid The key ID to include in the header
 * @param kmsKeyRef The KMS key reference (alias). If null, uses kid.
 * @return FederationResult containing the signed JWT compact string or an error.
 */
suspend inline fun <reified T> JwtService.signPayload(
    payload: T,
    header: JwtHeader,
    kid: String,
    kmsKeyRef: String?
): FederationResult<String> {
    return try {
        // Serialize the payload to JsonObject
        val payloadJson = Json.encodeToJsonElement(Json.serializersModule.serializer<T>(), payload).jsonObject

        // Use alias if available, otherwise use kid
        val issuerOpts = if (kmsKeyRef != null) {
            ManagedOptsAlias(identifier = kmsKeyRef)
        } else {
            ManagedOptsKid(identifier = kid)
        }

        // Create the JWS using IDK's JwtService
        val args = CreateJwsArgs(
            issuer = issuerOpts,
            payload = payloadJson,
            mode = JwsIdentifierMode.KID,
            opts = CreateJwsOpts(
                protectedHeader = header.toJsonObject(),
                noIssPayloadUpdate = true  // Don't modify the payload
            )
        )

        val result = createJwsCompact(args)

        if (result.isOk) {
            result.value.jwt.toOk()
        } else {
            JwtCreationFailedError(
                reason = result.error.message.defaultMessage,
                exception = null
            ).toErr()
        }
    } catch (e: Exception) {
        JwtCreationFailedError(
            reason = e.message ?: "Unknown signing error",
            exception = e
        ).toErr()
    }
}

package com.sphereon.oid.fed.common.mapper

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.JWTSignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class JsonMapper {

    /*
     * Used for mapping JWT token to EntityStatement object
     */
    fun mapEntityStatement(jwtToken: String): EntityConfigurationStatement? =
        decodeJWTComponents(jwtToken)?.payload?.let { Json.decodeFromJsonElement(it) }

    /*
     * Used for mapping trust chain
     */
    fun mapTrustChain(jwtTokenList: List<String>): List<EntityConfigurationStatement?> =
        jwtTokenList.map { mapEntityStatement(it) }

    /*
     * Used for decoding JWT to an object of JWT with Header, Payload and Signature
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeJWTComponents(jwtToken: String): JWT {
        val parts = jwtToken.split(".")
        if (parts.size != 3) {
            throw InvalidJwtException("Invalid JWT format: Expected 3 parts, found ${parts.size}")
        }

        val headerJson = Base64.decode(parts[0]).decodeToString()
        val payloadJson = Base64.decode(parts[1]).decodeToString()

        return try {
            JWT(
                Json.decodeFromString(headerJson), Json.parseToJsonElement(payloadJson), JWTSignature(parts[2])
            )
        } catch (e: Exception) {
            throw JwtDecodingException("Error decoding JWT components", e)
        }
    }

    data class JWT(val header: JWTHeader, val payload: JsonElement, val signature: JWTSignature)


    // Custom Exceptions
    class InvalidJwtException(message: String) : Exception(message)
    class JwtDecodingException(message: String, cause: Throwable) : Exception(message, cause)
}

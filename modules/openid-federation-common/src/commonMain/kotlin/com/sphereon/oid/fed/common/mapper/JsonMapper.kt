package com.sphereon.oid.fed.common.mapper

import com.sphereon.oid.fed.common.logging.Logger
import com.sphereon.oid.fed.openapi.models.EntityStatement
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
    fun mapEntityStatement(jwtToken: String): EntityStatement? =
        decodeJWTComponents(jwtToken)?.second?.let { Json.decodeFromJsonElement(it) }

    /*
     * Used for mapping trust chain
     */
    fun mapTrustChain(jwtTokenList: List<String>): List<EntityStatement?> = jwtTokenList.map { mapEntityStatement(it) }

    /*
     * Used for decoding JWT to a triple with Header, Payload and Signature
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeJWTComponents(jwtToken: String): Triple<JWTHeader, JsonElement, JWTSignature>? {
        val parts = jwtToken.split(".")
        if (parts.size != 3) {
            Logger.error(tag = "OIDF", message = "Invalid JWT format: Expected 3 parts, found ${parts.size}")
            return null
        }

        val headerJson = Base64.decode(parts[0]).decodeToString()
        val payloadJson = Base64.decode(parts[1]).decodeToString()

        return try {
            Triple(
                Json.decodeFromString(headerJson), Json.parseToJsonElement(payloadJson), JWTSignature(parts[2])
            )
        } catch (e: Exception) {
            Logger.error(tag = "OIDF", message = "Error decoding from string", e)
            return null
        }
    }
}

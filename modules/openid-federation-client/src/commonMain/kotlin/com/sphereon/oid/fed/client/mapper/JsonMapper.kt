package com.sphereon.oid.fed.client.mapper

import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.JWTSignature
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.reflect.KClass


    /*
     * Used for mapping JWT token to EntityStatement object
     */
    @OptIn(InternalSerializationApi::class)
    fun <T : Any> mapEntityStatement(jwtToken: String, targetType: KClass<T>): T? {
        val payload: JsonElement = decodeJWTComponents(jwtToken).payload
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
        }.decodeFromJsonElement(targetType.serializer(), payload)
    }

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

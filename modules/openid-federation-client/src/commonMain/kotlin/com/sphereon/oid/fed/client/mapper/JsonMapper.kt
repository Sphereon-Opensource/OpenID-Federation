package com.sphereon.oid.fed.client.mapper

import com.sphereon.oid.fed.openapi.models.JWT
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass


private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/*
     * Used for mapping JWT token to EntityStatement object
     */
@OptIn(InternalSerializationApi::class)
fun <T : Any> mapEntityStatement(jwtToken: String, targetType: KClass<T>): T? {
    val payload: JsonObject = decodeJWTComponents(jwtToken).payload
    return json.decodeFromJsonElement(targetType.serializer(), payload)
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
            Json.decodeFromString(headerJson), Json.decodeFromString(payloadJson), parts[2]
        )
    } catch (e: Exception) {
        throw JwtDecodingException("Error decoding JWT components", e)
    }
}

// Custom Exceptions
class InvalidJwtException(message: String) : Exception(message)
class JwtDecodingException(message: String, cause: Throwable) : Exception(message, cause)

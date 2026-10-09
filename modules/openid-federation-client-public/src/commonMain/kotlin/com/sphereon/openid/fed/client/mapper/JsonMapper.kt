package com.sphereon.openid.fed.client.mapper

import com.sphereon.core.api.json.CheckedJsonObjectDeserializer
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass


val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

private val checkedPolicyOperators = CheckedJsonObjectDeserializer()
private val checkedPolicyParameters = CheckedJsonObjectDeserializer { checkedPolicyOperators }
private val checkedPolicyRoles = CheckedJsonObjectDeserializer { checkedPolicyParameters }
private val checkedJwtPayload = CheckedJsonObjectDeserializer { name ->
    if (name == "metadata_policy") checkedPolicyRoles else JsonElement.serializer()
}

/*
 * Used for mapping JWT token to EntityStatement object
 */
@OptIn(InternalSerializationApi::class)
inline fun <reified T : Any> mapEntityStatement(jwtToken: String, targetType: KClass<T>): T? {
    val payload: JsonObject = decodeJWTComponents(jwtToken).payload
    return json.decodeFromString(payload.toString())
}

/*
 * Used for decoding JWT to an object of JWT with Header, Payload and Signature
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeJWTComponents(jwtToken: String): Jwt {
    val parts = jwtToken.split(".")
    if (parts.size != 3) {
        throw InvalidJwtException("Invalid JWT format: Expected 3 parts, found ${parts.size}")
    }

    val headerJson = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parts[0]).decodeToString()
    val payloadJson = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parts[1]).decodeToString()

    return try {
        val header = json.parseToJsonElement(headerJson).jsonObject
        if (header["typ"]?.jsonPrimitive?.contentOrNull == EntityStatementValidation.ENTITY_STATEMENT_TYP) {
            for (name in listOf("trust_chain", "peer_trust_chain")) {
                if (header.containsKey(name)) {
                    throw IllegalArgumentException("entity-statement+jwt must not contain '$name' JOSE header")
                }
            }
        }
        Jwt(
            json.decodeFromJsonElement<JwtHeader>(header),
            json.decodeFromString(checkedJwtPayload, payloadJson),
            parts[2],
        )
    } catch (e: Exception) {
        throw JwtDecodingException("Error decoding JWT components", e)
    }
}

class InvalidJwtException(message: String) : Exception(message)
class JwtDecodingException(message: String, cause: Throwable) : Exception(message, cause)

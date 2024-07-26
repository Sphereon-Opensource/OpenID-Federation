package com.sphereon.oid.fed.common.mapper

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
    fun mapEntityStatement(jwtToken: String): EntityStatement? {
        val data = decodeJWTComponents(jwtToken)
        return if (data.second != null) {
            Json.decodeFromJsonElement(data.second!!)
        } else {
            null
        }
    }

    /*
     * Used for mapping trust chain
     */
    fun mapTrustChain(jwtTokenList: List<String>): List<EntityStatement?> {
        val list: MutableList<EntityStatement?> = mutableListOf()
        jwtTokenList.forEach { jwtToken ->
            list.add(mapEntityStatement(jwtToken))
        }
        return list
    }

    /*
     * Used for decoding JWT to a triple with Header, Payload and Signature
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeJWTComponents(jwtToken: String): Triple<JWTHeader?, JsonElement?, JWTSignature?> {
        val parts = jwtToken.split(".")
        if (parts.size != 3) {
            return Triple(null, null, null)
        }

        val headerJson = Base64.decode(parts[0]).decodeToString()
        val payloadJson = Base64.decode(parts[1]).decodeToString()

        return try {
            Triple(
                Json.decodeFromString(headerJson), Json.parseToJsonElement(payloadJson), JWTSignature(parts[2])
            )
        } catch (e: Exception) {
            println(e.printStackTrace())
            Triple(null, null, null)
        }
    }
}

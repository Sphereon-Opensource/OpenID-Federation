package com.sphereon.oid.fed.common.mapper

import com.sphereon.oid.fed.common.model.JWTHeader
import com.sphereon.oid.fed.common.model.JWTSignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


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

package com.sphereon.oid.fed.client.fetch

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.JWTSignature
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class JWT(val header: JWTHeader, val payload: JsonObject, val signature: JWTSignature)

class InvalidJwtException(message: String) : Exception(message)
class JwtDecodingException(message: String, cause: Throwable) : Exception(message, cause)

class Fetch(engine: HttpClientEngine) {
    private val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
    }

    fun getEntityConfigurationEndpoint(iss: String): String {
        val sb = StringBuilder()
        sb.append(iss.trim('"'))
        sb.append("/.well-known/openid-federation")
        return sb.toString()
    }

    fun getSubordinateStatementEndpoint(fetchEndpoint: String, sub: String): String {
        val sb = StringBuilder()
        sb.append(fetchEndpoint.trim('"'))
        sb.append("?sub=")
        sb.append(sub)
        return sb.toString()
    }

    suspend fun fetchStatement(endpoint: String): String? {
        val response = httpClient.get(endpoint)

        return response.body()
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun String.decodeJWTComponents(): JWT {
    val parts = this.split(".")
    if (parts.size != 3) {
        throw InvalidJwtException("Invalid JWT format: Expected 3 parts, found ${parts.size}")
    }

    val headerJson = Base64.decode(parts[0]).decodeToString()
    val payloadJson = Base64.decode(parts[1]).decodeToString()

    return try {
        JWT(
            Json.decodeFromString(headerJson), Json.decodeFromString(payloadJson), JWTSignature(parts[2])
        )
    } catch (e: Exception) {
        throw JwtDecodingException("Error decoding JWT components", e)
    }
}

fun JWT.toEntityConfiguration(): EntityConfigurationStatement {
    return this.payload.let {
        Json {
            ignoreUnknownKeys = true
        }.decodeFromJsonElement(it)
    }
}

fun JWT.toSubordinateStatement(): JsonObject {
    return this.payload.let {
        Json {
            ignoreUnknownKeys = true
        }.decodeFromJsonElement(it)
    }
}

package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

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
        val response = httpClient.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
            }
        }

        return response.body()
    }
}

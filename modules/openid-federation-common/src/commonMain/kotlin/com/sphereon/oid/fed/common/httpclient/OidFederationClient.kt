package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.openapi.models.EntityStatement
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.core.*

class OidFederationClient(
    engine: HttpClientEngine,
    private val isRequestAuthenticated: Boolean = false,
    private val isRequestCached: Boolean = false
) {
    private val BASE_URL = "https://www.example.com"

    private val client: HttpClient = HttpClient(engine) {
        install(HttpCache)
        install(ContentNegotiation) {
            register(EntityStatementJwt, EntityStatementJwtConverter())
            json()
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    com.sphereon.oid.fed.common.logging.Logger.info("API", message)
                }
            }
            level = LogLevel.ALL
        }
        if (isRequestAuthenticated) {
            install(Auth) {
                bearer {
                    loadTokens {
                        //TODO add correct implementation later
                        BearerTokens("accessToken", "refreshToken")
                    }
                }
            }
        }
        if (isRequestCached) {
            install(HttpCache)
        }
    }

    suspend fun fetchEntityStatement(httpMethod: HttpMethod = Get, parameters: Parameters = Parameters.Empty): EntityStatement {
        return when (httpMethod) {
            Get -> getEntityStatement(parameters)
            Post -> postEntityStatement(parameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend fun getEntityStatement(parameters: Parameters): EntityStatement {
        // Appends parameters to the URL
        val urlWithParams = if (parameters.isEmpty()) BASE_URL else "$BASE_URL?${parameters.formUrlEncode()}"

        return client.use { it.get(urlWithParams).body<EntityStatement>() }
    }

    private suspend fun postEntityStatement(parameters: Parameters): EntityStatement {
        return client.use {
            it.post(BASE_URL) {
                setBody(FormDataContent(parameters))
            }.body<EntityStatement>()
        }
    }
}

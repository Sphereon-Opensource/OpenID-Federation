package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
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
    private val client: HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            register(EntityStatementJwt, EntityConfigurationStatementJwtConverter())
            json()
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
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

    suspend fun fetchEntityConfigurationStatement(
        identifier: String,
        httpMethod: HttpMethod = Get,
        parameters: Parameters = Parameters.Empty
    ): EntityConfigurationStatement {
        val wellKnownUrl = "$identifier/.well-known/openid-federation"
        return when (httpMethod) {
            Get -> fetchGetStatement(wellKnownUrl)
            Post -> fetchPostStatement(wellKnownUrl, parameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    suspend fun fetchSubordinateStatement(
        iss: String,
        sub: String,
        fetchUrl: String,
        httpMethod: HttpMethod = Get,
    ): SubordinateStatement {
        return when (httpMethod) {
            Get -> fetchGetStatement("$fetchUrl?iss=$iss&sub=$sub")
            Post -> fetchPostStatement(fetchUrl, Parameters.build {
                append("iss", iss)
                append("sub", sub)
            })

            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend inline fun <reified T> fetchGetStatement(url: String): T =
        client.use { it.get(url).body() }

    private suspend inline fun <reified T> fetchPostStatement(url: String, parameters: Parameters): T =
        client.use {
            it.post(url) {
                setBody(FormDataContent(parameters))
            }.body()
        }
}

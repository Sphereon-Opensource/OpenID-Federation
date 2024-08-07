package com.sphereon.oid.fed.common.httpclient

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.utils.io.core.*

class OidFederationClient(
    engine: HttpClientEngine,
    private val isRequestAuthenticated: Boolean = false,
    private val isRequestCached: Boolean = false
) {
    private val client: HttpClient = HttpClient(engine) {
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

    suspend fun fetchEntityStatement(url: String, httpMethod: HttpMethod = Get, parameters: Parameters = Parameters.Empty): String {
        return when (httpMethod) {
            Get -> getEntityStatement(url)
            Post -> postEntityStatement(url, parameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend fun getEntityStatement(url: String): String {
        return client.use { it.get(url).body() }
    }

    private suspend fun postEntityStatement(url: String, parameters: Parameters): String {
        return client.use {
            it.post(url) {
                setBody(FormDataContent(parameters))
            }.body()
        }
    }
}

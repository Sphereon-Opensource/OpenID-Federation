package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.IHttpClientService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.utils.io.core.use
import java.lang.IllegalArgumentException

class MockHttpClientCallbackService(engine: HttpClientEngine) : IHttpClientService {

    private val isRequestAuthenticated: Boolean = false
    private val isRequestCached: Boolean = false

    private val client: HttpClient = HttpClient(engine) {
            install(HttpCache.Companion)
            install(Logging) {
                logger = Logger.Companion.DEFAULT
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
                install(HttpCache.Companion)
        }
    }

    override suspend fun fetchEntityStatement(
        url: String,
        httpMethod: HttpMethod,
        parameters: Parameters
    ): String {
        return when (httpMethod) {
            HttpMethod.Companion.Get -> getEntityStatement(url)
            HttpMethod.Companion.Post -> postEntityStatement(url, parameters)
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

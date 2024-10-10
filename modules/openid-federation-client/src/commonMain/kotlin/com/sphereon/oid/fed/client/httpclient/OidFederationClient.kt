package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.client.ICallbackService
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.request.forms.*
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.Parameters.Companion.Empty
import io.ktor.utils.io.core.use
import kotlin.jvm.JvmStatic

interface IHttpClientService {
    suspend fun fetchEntityStatement(
        url: String,
        httpMethod: HttpMethod = Get,
        parameters: Parameters = Empty
    ): String
}

interface HttpClientCallbackService: ICallbackService<IHttpClientService>, IHttpClientService

expect fun httpService(): HttpClientCallbackService

object OidFederationHttpClientObject: HttpClientCallbackService {

    private val isRequestAuthenticated: Boolean = false
    private val isRequestCached: Boolean = false
    private var disabled: Boolean = false

    @JvmStatic
    private lateinit var engine: HttpClientEngine

    @JvmStatic
    private lateinit var platformCallback: IHttpClientService

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
        if (!isEnabled()) {
            HttpConst.LOG.info("HTTP fetchEntityStatement has been disabled")
            throw IllegalStateException("HTTP service is disabled; cannot fetch")
        } else if (!this::platformCallback.isInitialized) {
            HttpConst.LOG.error("HTTP callback (JS) is not registered")
            throw IllegalStateException("HTTP have not been initialized. Please register your HttpClientCallbacksJS implementation, or register a default implementation")
        }
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

    override fun disable(): IHttpClientService {
        this.disabled = true
        return this
    }

    override fun enable(): IHttpClientService {
        this.disabled = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun register(platformCallback: IHttpClientService): HttpClientCallbackService {
        this.platformCallback = platformCallback
        return this
    }
}

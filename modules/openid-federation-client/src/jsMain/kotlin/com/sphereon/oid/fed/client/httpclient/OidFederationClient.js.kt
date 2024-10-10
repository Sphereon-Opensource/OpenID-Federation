package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.client.ICallbackService
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
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.Parameters
import io.ktor.utils.io.core.use
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise

@ExperimentalJsExport
@JsExport
interface IHttpClientServiceJS {
    fun fetchEntityStatement(
        url: String,
        httpMethod: dynamic,
        parameters: dynamic
    ): Promise<String>
}

@ExperimentalJsExport
@JsExport
object OidFederationHttpClientJS: ICallbackService<IHttpClientServiceJS>, IHttpClientServiceJS {

    private val isRequestAuthenticated: Boolean = false
    private val isRequestCached: Boolean = false
    private var disabled: Boolean = false
    val NAME = "OidFederationClientObject"

    private lateinit var engine: HttpClientEngine
    private lateinit var platformCallback: IHttpClientServiceJS

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

    override fun fetchEntityStatement(
        url: String,
        httpMethod: dynamic,
        parameters: dynamic
    ): Promise<String> {
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

    private fun getEntityStatement(url: String): Promise<String> = CoroutineScope(context = CoroutineName(NAME)).promise {
        client.use { it.get(url).body() }
    }

    private fun postEntityStatement(url: String, parameters: Parameters): Promise<String> = CoroutineScope(context = CoroutineName(NAME)).promise {
        client.use {
            it.post(url) {
                setBody(FormDataContent(parameters))
            }.body()
        }
    }

    override fun disable(): IHttpClientServiceJS {
        this.disabled = true
        return this
    }

    override fun enable(): IHttpClientServiceJS {
        this.disabled = false
        return this
    }

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun register(platformCallback: IHttpClientServiceJS): OidFederationHttpClientJS {
        this.platformCallback = platformCallback
        return this
    }
}

open class OidFederationClientJSAdapter(val httpCallbackJS: OidFederationHttpClientJS): HttpClientCallbackService {

    override fun disable(): IHttpClientService {
        this.httpCallbackJS.disable()
        return this
    }

    override fun enable(): IHttpClientService {
        this.httpCallbackJS.enable()
        return this
    }

    override fun isEnabled(): Boolean {
        return this.httpCallbackJS.isEnabled()
    }

    override fun register(platformCallback: IHttpClientService): IHttpClientService {
        throw Error("Register function should not be used on the adapter. It depends on the Javascript HttpService object")
    }

    override suspend fun fetchEntityStatement(
        url: String,
        httpMethod: HttpMethod,
        parameters: Parameters
    ): String {
        HttpConst.LOG.debug("Calling HTTP fetchEntityStatement...")

        return try {
            httpCallbackJS.fetchEntityStatement(url, httpMethod, parameters).await()
        } catch (e: Exception) {
            throw e
        }.also {
            HttpConst.LOG.info("Calling HTTP fetchEntityStatement result: $it")
        }
    }
}

object OidFederationClientServiceJSAdapterObject:  OidFederationClientJSAdapter(OidFederationHttpClientJS)

actual fun httpService(): HttpClientCallbackService = OidFederationClientServiceJSAdapterObject

package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.client.ICallbackService
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.Parameters.Companion.Empty
import kotlin.jvm.JvmStatic

/**
 * The main interface used for the platform specific callback. Has to be implemented by external developers.
 *
 * Not exported to JS as it has a similar interface exported using Promises instead of coroutines
 */
interface IHttpClientService {
    suspend fun fetchEntityStatement(
        url: String,
        httpMethod: HttpMethod = Get,
        parameters: Parameters = Empty
    ): String
}

interface HttpClientCallbackService: ICallbackService<IHttpClientService>, IHttpClientService

expect fun httpService(): HttpClientCallbackService

/**
 * The main entry point for HTTP client, delegating to a platform specific callback implemented by external developers
 */
object OidFederationHttpClientObject: HttpClientCallbackService {

    private var disabled: Boolean = false

    @JvmStatic
    private lateinit var platformCallback: IHttpClientService

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
       return platformCallback.fetchEntityStatement(url, httpMethod, parameters)
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

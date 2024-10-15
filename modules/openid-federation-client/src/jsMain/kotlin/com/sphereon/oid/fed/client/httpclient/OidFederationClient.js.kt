package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.client.ICallbackService
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.coroutines.await
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

    private var disabled: Boolean = false
    val NAME = "OidFederationClientObject"

    private lateinit var platformCallback: IHttpClientServiceJS

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
        return platformCallback.fetchEntityStatement(url, httpMethod, parameters)
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

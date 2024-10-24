package com.sphereon.oid.fed.client.fetch

import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.client.service.ICallbackService
import io.ktor.client.*
import kotlin.js.JsExport

expect interface IFetchCallbackMarkerType
interface IFetchMarkerType

@JsExport.Ignore
interface IFetchCallbackService: IFetchCallbackMarkerType {
    suspend fun fetchStatement(
        endpoint: String
    ): String
    suspend fun getHttpClient(): HttpClient
}

@JsExport.Ignore
interface IFetchService: IFetchMarkerType {
    suspend fun fetchStatement(
        endpoint: String
    ): String
    suspend fun getHttpClient(): HttpClient
}

expect fun fetchService(platformCallback: IFetchCallbackMarkerType = DefaultCallbacks.fetchService()): IFetchService

abstract class AbstractFetchService<CallbackServiceType>(open val platformCallback: CallbackServiceType): ICallbackService<CallbackServiceType> {
    private var disabled = false

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun disable() = apply {
        this.disabled = true
    }

    override fun enable() = apply {
        this.disabled = false
    }

    protected fun assertEnabled() {
        if (!isEnabled()) {
            FetchConst.LOG.info("CRYPTO verify has been disabled")
            throw IllegalStateException("CRYPTO service is disable; cannot verify")
        } else if (this.platformCallback == null) {
            FetchConst.LOG.error("CRYPTO callback is not registered")
            throw IllegalStateException("CRYPTO has not been initialized. Please register your CryptoCallback implementation, or register a default implementation")
        }
    }
}

class FetchService(override val platformCallback: IFetchCallbackService = DefaultCallbacks.jwtService()): AbstractFetchService<IFetchCallbackService>(platformCallback), IFetchService {

    override fun platform(): IFetchCallbackService {
        return this.platformCallback
    }

    override suspend fun fetchStatement(endpoint: String): String {
        return this.platformCallback.fetchStatement(endpoint)
    }

    override suspend fun getHttpClient(): HttpClient {
        return this.platformCallback.getHttpClient()
    }
}

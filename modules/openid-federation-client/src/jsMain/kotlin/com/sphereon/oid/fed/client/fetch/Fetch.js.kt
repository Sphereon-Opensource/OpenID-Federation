package com.sphereon.oid.fed.client.fetch

import kotlinx.coroutines.await
import kotlin.js.Promise

@JsExport
external interface ICallbackServiceJS<PlatformCallbackType> {
    @JsName("register")
    fun register(platformCallback: PlatformCallbackType): ICallbackServiceJS<PlatformCallbackType>
}

@JsExport
external interface IFetchCallbackJS {
    @JsName("fetchStatement")
    fun fetchStatement(
        endpoint: String
    ): Promise<String>
}

@JsExport
object FetchServiceJS : ICallbackServiceJS<IFetchCallbackJS>, IFetchCallbackJS {
    private lateinit var platformCallback: IFetchCallbackJS

    override fun register(platformCallback: IFetchCallbackJS): FetchServiceJS {
        this.platformCallback = platformCallback
        return this
    }

    override fun fetchStatement(endpoint: String): Promise<String> {
        return this.platformCallback.fetchStatement(endpoint)
    }
}

open class FetchServiceJSAdapter(private val fetchCallbackJS: FetchServiceJS = FetchServiceJS) : IFetchCallbackService {
    override suspend fun fetchStatement(endpoint: String): String {
        return fetchCallbackJS.fetchStatement(endpoint).await()
    }

    override fun register(platformCallback: IFetchService): IFetchCallbackService {
        throw Error("Register function should not be used on the adapter.")
    }
}

object FetchServiceJSAdapterObject : FetchServiceJSAdapter(FetchServiceJS)

actual fun fetchService(): IFetchCallbackService = FetchServiceJSAdapterObject

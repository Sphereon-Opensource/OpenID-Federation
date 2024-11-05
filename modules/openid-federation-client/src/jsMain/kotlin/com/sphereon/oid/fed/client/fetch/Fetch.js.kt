package com.sphereon.oid.fed.client.fetch

import com.sphereon.oid.fed.client.crypto.AbstractCryptoService
import com.sphereon.oid.fed.client.service.DefaultCallbacks
import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.request.get
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.headers
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlin.js.Promise


@JsExport
interface IFetchCallbackServiceJS: IFetchCallbackMarkerType {
    fun fetchStatement(
        endpoint: String
    ): Promise<String>
    fun getHttpClient(): Promise<HttpClient>
}

@JsExport.Ignore
interface IFetchServiceJS: IFetchMarkerType {
    fun fetchStatement(
        endpoint: String
    ): Promise<String>
    fun getHttpClient(): Promise<HttpClient>
}

private const val FETCH_SERVICE_JS_SCOPE = "FetchServiceJS"

@JsExport
class FetchServiceJS(override val platformCallback: IFetchCallbackServiceJS = DefaultCallbacks.fetchService()): AbstractCryptoService<IFetchCallbackServiceJS>(platformCallback), IFetchServiceJS {

    override fun platform(): IFetchCallbackServiceJS {
        return this.platformCallback
    }

    override fun fetchStatement(endpoint: String): Promise<String> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            return@async platformCallback.fetchStatement(endpoint).await()
        }.asPromise()
    }

    override fun getHttpClient(): Promise<HttpClient> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            return@async platformCallback.getHttpClient().await()
        }.asPromise()
    }
}

class FetchServiceJSAdapter(val fetchCallbackJS: FetchServiceJS = FetchServiceJS()): AbstractFetchService<IFetchCallbackServiceJS>(fetchCallbackJS.platformCallback), IFetchService {

    override fun platform(): IFetchCallbackServiceJS = fetchCallbackJS.platformCallback

    override suspend fun fetchStatement(endpoint: String): String {
        val result = this.platformCallback.fetchStatement(endpoint).await()
        return result
    }

    override suspend fun getHttpClient(): HttpClient = this.platformCallback.getHttpClient().await()
}

@JsExport.Ignore
actual fun fetchService(platformCallback: IFetchCallbackMarkerType): IFetchService {
    val jsPlatformCallback = platformCallback.unsafeCast<IFetchCallbackServiceJS>()
    if (jsPlatformCallback === undefined) {
        throw IllegalStateException("Invalid platform callback supplied: Needs to be of type IFetchCallbackServiceJS, but is of type ${platformCallback::class::simpleName} instead")
    }
    return FetchServiceJSAdapter(FetchServiceJS(jsPlatformCallback))
}

@JsExport
actual external interface IFetchCallbackMarkerType

@JsExport
class DefaultFetchJSImpl : IFetchCallbackServiceJS {

    private val FETCH_SERVICE_JS_SCOPE = "FetchServiceJS"

    override fun getHttpClient(): Promise<HttpClient> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            return@async HttpClient(Js)
        }.asPromise()
    }

    override fun fetchStatement(endpoint: String): Promise<String> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            val client = getHttpClient().await()
            val response = client.get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }
            return@async response.bodyAsText()
        }.asPromise()
    }
}

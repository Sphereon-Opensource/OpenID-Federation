package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlin.js.JsExport
import kotlin.jvm.JvmStatic

interface ICallbackService<PlatformCallbackType> {
    fun register(platformCallback: PlatformCallbackType?): ICallbackService<PlatformCallbackType>
}

interface IFetchService {
    fun getHttpClient(): HttpClient
}

interface IFetchServiceInternal {
    fun fetchStatement(
        endpoint: String
    ): Deferred<String>
}

interface IFetchCallbackService : ICallbackService<IFetchService>, IFetchService, IFetchServiceInternal

@JsExport
object FetchServiceObject : IFetchCallbackService {
    @JvmStatic
    private lateinit var platformCallback: IFetchService
    private lateinit var httpClient: HttpClient

    override fun fetchStatement(endpoint: String): Deferred<String> {
        return GlobalScope.async {
            httpClient.get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }.body()
        }
    }

    override fun getHttpClient(): HttpClient {
        return this.platformCallback.getHttpClient()
    }

    override fun register(platformCallback: IFetchService?): IFetchCallbackService {

        class DefaultPlatformCallback : IFetchService {
            override fun getHttpClient(): HttpClient {
                return HttpClient()
            }
        }

        this.platformCallback = platformCallback ?: DefaultPlatformCallback()
        this.httpClient = this.platformCallback.getHttpClient()
        return this
    }
}

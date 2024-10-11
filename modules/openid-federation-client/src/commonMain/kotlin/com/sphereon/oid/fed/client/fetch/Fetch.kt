package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.jvm.JvmStatic

interface ICallbackService<PlatformCallbackType> {
    fun register(platformCallback: PlatformCallbackType): ICallbackService<PlatformCallbackType>
}

interface IFetchService {
    fun getHttpClient(): HttpClient
}

interface IFetchServiceInternal {
    suspend fun fetchStatement(
        endpoint: String
    ): String
}

interface IFetchCallbackService : ICallbackService<IFetchService>, IFetchService, IFetchServiceInternal

expect fun fetchService(): IFetchCallbackService

object FetchServiceObject : IFetchCallbackService {
    @JvmStatic
    private lateinit var platformCallback: IFetchService
    private lateinit var httpClient: HttpClient

    override suspend fun fetchStatement(endpoint: String): String {
        return this.httpClient.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
            }
        }.body()
    }

    override fun getHttpClient(): HttpClient {
        return this.platformCallback.getHttpClient()
    }

    override fun register(platformCallback: IFetchService): IFetchCallbackService {
        this.platformCallback = platformCallback
        this.httpClient = this.platformCallback.getHttpClient()
        return this
    }
}

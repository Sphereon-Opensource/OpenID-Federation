package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface ICallbackService<PlatformCallbackType> {
    fun register(platformCallback: PlatformCallbackType?): ICallbackService<PlatformCallbackType>
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

object FetchServiceObject : IFetchCallbackService {
    private lateinit var platformCallback: IFetchService
    private lateinit var httpClient: HttpClient

    override suspend fun fetchStatement(endpoint: String): String {
        return httpClient.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
            }
        }.body()
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

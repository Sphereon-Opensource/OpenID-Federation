package com.sphereon.oid.fed.client.fetch

import com.sphereon.oid.fed.client.types.ICallbackService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*


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
        return httpClient
            .get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }.body()
    }

    override fun getHttpClient(): HttpClient {
        return this.platformCallback.getHttpClient()
    }

    class DefaultPlatformCallback : IFetchService {
        override fun getHttpClient(): HttpClient {
            return HttpClient()
        }
    }

    override fun register(platformCallback: IFetchService?): IFetchCallbackService {
        this.platformCallback = platformCallback ?: DefaultPlatformCallback()
        this.httpClient = this.platformCallback.getHttpClient()
        return this
    }
}

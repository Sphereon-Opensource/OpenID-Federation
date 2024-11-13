package com.sphereon.oid.fed.client.fetch

import IFetchServiceJS
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.js.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.await

class FetchServiceAdapter(private val jsFetchService: IFetchServiceJS) : IFetchService {
    override suspend fun fetchStatement(endpoint: String): String {
        return jsFetchService.fetchStatement(endpoint).await()
    }
}

actual fun fetchService(): IFetchService {
    return object : IFetchService {
        private val httpClient = HttpClient(Js)

        override suspend fun fetchStatement(endpoint: String): String {
            return httpClient.get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }.body()
        }
    }
}

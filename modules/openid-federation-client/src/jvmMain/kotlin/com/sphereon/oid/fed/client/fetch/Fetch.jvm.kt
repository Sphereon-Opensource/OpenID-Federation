package com.sphereon.oid.fed.client.fetch

import com.sphereon.oid.fed.client.types.IFetchService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.request.*
import io.ktor.http.*

actual fun fetchService(): IFetchService {
    return object : IFetchService {
        private val httpClient = HttpClient(Java)

        override suspend fun fetchStatement(endpoint: String): String {
            return httpClient.get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }.body()
        }
    }
}

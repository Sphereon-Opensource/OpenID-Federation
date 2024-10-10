package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*

expect fun getHttpClient(httpClientEngine: HttpClientEngine?): HttpClient

class Fetch(engine: HttpClientEngine?) {
    private val client = getHttpClient(engine)

    suspend fun fetchStatement(endpoint: String): String {
        val response = client.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
            }
        }

        return response.body()
    }
}

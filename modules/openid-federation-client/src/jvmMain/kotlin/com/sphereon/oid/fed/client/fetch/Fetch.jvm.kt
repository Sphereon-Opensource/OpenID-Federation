package com.sphereon.oid.fed.client.fetch

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*

actual fun getHttpClient(httpClientEngine: HttpClientEngine?): HttpClient {
    return HttpClient(httpClientEngine ?: CIO.create())
}

package com.sphereon.oid.fed.client.fetch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.*
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headers

actual fun fetchService(platformCallback: IFetchCallbackMarkerType): IFetchService {
    if (platformCallback !is IFetchCallbackService) {
        throw IllegalArgumentException("Platform callback is not of type IFetchCallbackService, but ${platformCallback.javaClass.canonicalName}")
    }
    return FetchService(platformCallback)
}

actual interface IFetchCallbackMarkerType

class DefaultFetchJvmImpl : IFetchCallbackService {
    override suspend fun fetchStatement(endpoint: String): String {
        return getHttpClient().get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
                append(HttpHeaders.AcceptCharset, "ascii, utf-8")
            }
        }.body() as String
    }

    override suspend fun getHttpClient(): HttpClient {
        return HttpClient(Java)
    }
}

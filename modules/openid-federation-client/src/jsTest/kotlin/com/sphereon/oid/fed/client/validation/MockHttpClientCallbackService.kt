package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.IHttpClientServiceJS
import com.sphereon.oid.fed.client.httpclient.OidFederationHttpClientJS.NAME
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.Parameters
import io.ktor.utils.io.core.use
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

class MockHttpClientCallbackServiceJS(engine: HttpClientEngine) : IHttpClientServiceJS {

    private val client: HttpClient = HttpClient(engine)

    override fun fetchEntityStatement(
        url: String,
        httpMethod: HttpMethod,
        parameters: Parameters
    ): Promise<String> {
        return when (httpMethod) {
            Get -> getEntityStatement(url)
            Post -> postEntityStatement(url, parameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private fun getEntityStatement(url: String): Promise<String> =
        CoroutineScope(context = CoroutineName(NAME)).promise {
            client.use { it.get(url).body() }
        }

    private fun postEntityStatement(url: String, parameters: Parameters): Promise<String> =
        CoroutineScope(context = CoroutineName(NAME)).promise {
            client.use {
                it.post(url) {
                    setBody(FormDataContent(parameters))
                }.body()
            }
        }
}

package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.common.jwt.KMSInterface
import com.sphereon.oid.fed.openapi.models.JWTHeader
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.utils.io.core.*
import kotlinx.serialization.json.JsonObject

class OidFederationClient(
    engine: HttpClientEngine,
    // TODO need KMS implementation
    //private val kmsInterface: KMSInterface,
    private val isRequestAuthenticated: Boolean = false,
    private val isRequestCached: Boolean = false,
) {
    private val client: HttpClient = HttpClient(engine) {
        install(HttpCache)
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    com.sphereon.oid.fed.common.logging.Logger.info("API", message)
                }
            }
            level = LogLevel.ALL
        }
        if (isRequestAuthenticated) {
            install(Auth) {
                bearer {
                    loadTokens {
                        //TODO add correct implementation later
                        BearerTokens("accessToken", "refreshToken")
                    }
                }
            }
        }
        if (isRequestCached) {
            install(HttpCache)
        }
    }

    suspend fun fetchEntityStatement(
        url: String, httpMethod: HttpMethod = Get, postParameters: PostEntityParameters? = null
    ): String {

        return when (httpMethod) {
            Get -> getEntityStatement(url)
            Post -> postEntityStatement(url, postParameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend fun getEntityStatement(url: String): String {
        return client.use { it.get(url).body() }
    }

    private suspend fun postEntityStatement(url: String, postParameters: PostEntityParameters?): String {
        val body = postParameters?.let { params ->
            // TODO need KMS implementation
            //kmsInterface.createJWT(header = params.header, payload = params.payload)
            params.payload.toString()
        }

        return client.use {
            it.post(url) {
                setBody(body)
            }.body()
        }
    }

    suspend fun fetchAccount(
        url: String, httpMethod: HttpMethod = Get, parameters: Parameters = Parameters.Empty
    ): String {
        return when (httpMethod) {
            Get -> getAccount(url)
            Post -> postAccount(url, parameters)
            Delete -> deleteAccount(url)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend fun getAccount(url: String): String {
        return client.use { it.get(url).body() }
    }

    private suspend fun postAccount(url: String, parameters: Parameters): String {
        return client.use {
            it.post(url) {
                setBody(FormDataContent(parameters))
            }.body()
        }
    }

    private suspend fun deleteAccount(url: String): String {
        return client.use { it.delete(url).body() }
    }


    // Data class for POST parameters
    data class PostEntityParameters(
        val payload: JsonObject, val header: JWTHeader
    )
}

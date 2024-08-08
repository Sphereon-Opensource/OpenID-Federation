package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.common.jwt.sign
import com.sphereon.oid.fed.openapi.models.EntityStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.json.JsonObject

class OidFederationClient(
    engine: HttpClientEngine,
    private val isRequestAuthenticated: Boolean = false,
    private val isRequestCached: Boolean = false
) {
    private val client: HttpClient = HttpClient(engine) {
        install(HttpCache)
        install(ContentNegotiation) {
            register(EntityStatementJwt, EntityStatementJwtConverter())
            json()
        }
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
    ): EntityStatement {
        return when (httpMethod) {
            Get -> getEntityStatement(url)
            Post -> postEntityStatement(url, postParameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    /*
     * GET call for Entity Statement
     */
    private suspend fun getEntityStatement(url: String): EntityStatement {
        return client.use { it.get(url).body<EntityStatement>() }
    }

    /*
     *  POST call for Entity Statement
     */
    private suspend fun postEntityStatement(url: String, postParameters: PostEntityParameters?): EntityStatement {
        val body = postParameters?.let { params ->
            sign(
                header = params.header,
                payload = params.payload,
                opts = mapOf("key" to params.key, "privateKey" to params.privateKey)
            )
        }

        return client.use {
            it.post(url) {
                setBody(body)
            }.body<EntityStatement>()
        }
    }


    // Data class for POST parameters
    data class PostEntityParameters(
        val payload: JsonObject, val header: JWTHeader, val key: String, val privateKey: String
    )
}

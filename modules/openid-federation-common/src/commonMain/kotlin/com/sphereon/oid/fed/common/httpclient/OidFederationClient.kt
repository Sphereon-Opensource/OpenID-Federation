package nl.zoe.httpclient

import com.sphereon.oid.fed.openapi.models.EntityStatement
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post

class OidFederationClient(
    private val client: HttpClient
) {
    suspend fun fetchEntityStatement(url: String, httpMethod: HttpMethod = Get, parameters: Parameters = Parameters.Empty): EntityStatement {
        return when (httpMethod) {
            Get -> getEntityStatement(url)
            Post -> postEntityStatement(url, parameters)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }
    }

    private suspend fun getEntityStatement(url: String): EntityStatement {
        return client.use { it.get(url).body<EntityStatement>() }
    }

    private suspend fun postEntityStatement(url: String, parameters: Parameters): EntityStatement {
        return client.use {
            it.post(url) {
                setBody(FormDataContent(parameters))
            }.body<EntityStatement>()
        }
    }
}
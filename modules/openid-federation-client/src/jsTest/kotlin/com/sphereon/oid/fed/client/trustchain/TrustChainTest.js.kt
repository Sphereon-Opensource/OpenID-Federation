package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.FederationClientJS
import com.sphereon.oid.fed.client.crypto.ICryptoCallbackServiceJS
import com.sphereon.oid.fed.client.fetch.IFetchCallbackServiceJS
import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.openapi.models.Jwk
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

actual class PlatformCallback : IFetchCallbackServiceJS {

    private val FETCH_SERVICE_JS_SCOPE = "FetchServiceTestJS"

    override fun getHttpClient(): Promise<HttpClient> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            return@async HttpClient(MockEngine { request ->
                val responseContent = mockResponses.find { it[0] == request.url.toString() }?.get(1)
                    ?: error("Unhandled ${request.url}")

                respond(
                    content = responseContent,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )
            })
        }.asPromise()
    }

    override fun fetchStatement(endpoint: String): Promise<String> {
        return CoroutineScope(context = CoroutineName(FETCH_SERVICE_JS_SCOPE)).async {
            return@async getHttpClient().await().get(endpoint) {
                headers {
                    append(HttpHeaders.Accept, "application/entity-statement+jwt")
                }
            }.body() as String
        }.asPromise()
    }
}

actual class CryptoCallbackService : ICryptoCallbackServiceJS {
    override fun verify(jwt: String, jwk: Jwk): Promise<Boolean> {
        return Promise.resolve(true)
    }
}

actual class TrustChainTest {
    @Test
    fun buildTrustChain() = runTest {
        val fetchService = PlatformCallback()
        DefaultCallbacks.setFetchServiceDefault(fetchService)
        val cryptoService = CryptoCallbackService()
        DefaultCallbacks.setCryptoServiceDefault(cryptoService)
        val trustChainService = DefaultTrustChainJSImpl()
        DefaultCallbacks.setTrustChainServiceDefault(trustChainService)

        val client = FederationClientJS()

        val trustChain = client.resolveTrustChainJS(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        ).await()

        assertNotNull(trustChain)

        assertEquals(4, trustChain.size)

        assertEquals(
            trustChain[0],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt/.well-known/openid-federation" }
                ?.get(1)
        )

        assertEquals(
            trustChain[1],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/fetch?sub=https://spid.wbss.it/Spid/oidc/rp/ipasv_lt&iss=https://spid.wbss.it/Spid/oidc/sa" }
                ?.get(1)
        )

        assertEquals(
            trustChain[2],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa&iss=https://oidc.registry.servizicie.interno.gov.it" }
                ?.get(1)
        )

        assertEquals(
            trustChain[3],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )

        val trustChain2 = client.resolveTrustChainJS(
            "https://spid.wbss.it/Spid/oidc/sa",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        ).await()

        assertNotNull(trustChain2)
        assertEquals(3, trustChain2.size)
        assertEquals(
            trustChain2[0],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/.well-known/openid-federation" }?.get(1)
        )

        assertEquals(
            trustChain2[1],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa&iss=https://oidc.registry.servizicie.interno.gov.it" }
                ?.get(1)
        )

        assertEquals(
            trustChain2[2],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )
    }
}

package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.fetch.IFetchCallbackService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.fetch.fetchService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlatformCallback(engine: HttpClientEngine) : IFetchService {
    private val httpClient = HttpClient(engine)

    override suspend fun fetchStatement(endpoint: String): String {
        return httpClient.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/entity-statement+jwt")
            }
        }.body()
    }
}

class TrustChainTest() {
    private val mockEngine = MockEngine { request ->
        val responseContent = mockResponses[request.url] ?: error("Unhandled ${request.url}")
        respond(
            content = responseContent,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
        )
    }
    private val platformCallback = PlatformCallback(mockEngine)

    @Test
    fun buildTrustChain() = runTest {
        val fetchService = fetchService().register(platformCallback)

        val trustChainService = TrustChain(fetchService as IFetchCallbackService)

        val trustChain = trustChainService.resolve(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertNotNull(trustChain)
        assertEquals(trustChain.size, 4)

        assertEquals(
            trustChain[0],
            mockResponses[Url("https://spid.wbss.it/Spid/oidc/rp/ipasv_lt/.well-known/openid-federation")]
        )

        assertEquals(
            trustChain[1],
            mockResponses[Url("https://spid.wbss.it/Spid/oidc/sa/fetch?sub=https://spid.wbss.it/Spid/oidc/rp/ipasv_lt")]
        )

        assertEquals(
            trustChain[2],
            mockResponses[Url("https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa")]
        )
        assertEquals(
            trustChain[3],
            mockResponses[Url("https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation")]
        )

        val trustChain2 = trustChainService.resolve(
            "https://spid.wbss.it/Spid/oidc/sa",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertNotNull(trustChain2)
        assertEquals(trustChain2.size, 3)
        assertEquals(
            trustChain2[0],
            mockResponses[Url("https://spid.wbss.it/Spid/oidc/sa/.well-known/openid-federation")]
        )
        assertEquals(
            trustChain2[1],
            mockResponses[Url("https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa")]
        )
        assertEquals(
            trustChain2[2],
            mockResponses[Url("https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation")]
        )
    }
}

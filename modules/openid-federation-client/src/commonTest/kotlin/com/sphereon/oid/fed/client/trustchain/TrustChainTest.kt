package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlatformCallback : IFetchService {
    override fun getHttpClient(): HttpClient {
        return HttpClient(MockEngine { request ->
            val responseContent = mockResponses.find { it[0] == request.url.toString() }?.get(1)
                ?: error("Unhandled ${request.url}")

            respond(
                content = responseContent,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        })
    }
}

class CryptoServiceCallback : ICryptoService {
    override suspend fun verify(jwt: String): Boolean {
        return true
    }
}

class TrustChainTest() {
    @Test
    fun buildTrustChain() = runTest {

        val client = FederationClient(PlatformCallback(), CryptoServiceCallback())

        val trustChain = client.resolveTrustChain(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertNotNull(trustChain)
        assertEquals(trustChain.size, 4)

        assertEquals(
            trustChain[0],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt/.well-known/openid-federation" }
                ?.get(1)
        )

        assertEquals(
            trustChain[1],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/fetch?sub=https://spid.wbss.it/Spid/oidc/rp/ipasv_lt" }
                ?.get(1)
        )

        assertEquals(
            trustChain[2],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa" }
                ?.get(1)
        )

        assertEquals(
            trustChain[3],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )

        val trustChain2 = client.resolveTrustChain(
            "https://spid.wbss.it/Spid/oidc/sa",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertNotNull(trustChain2)
        assertEquals(trustChain2.size, 3)
        assertEquals(
            trustChain2[0],
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/.well-known/openid-federation" }?.get(1)
        )

        assertEquals(
            trustChain2[1],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa" }
                ?.get(1)
        )

        assertEquals(
            trustChain2[2],
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )
    }
}

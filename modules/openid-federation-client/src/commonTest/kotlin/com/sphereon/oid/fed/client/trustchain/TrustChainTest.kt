package com.sphereon.oid.fed.client.trustchain

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TrustChainTest {

    private val mockEngine = MockEngine { request ->
        val responseContent = mockResponses[request.url] ?: error("Unhandled ${request.url}")
        respond(
            content = responseContent,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
        )
    }

    @Test
    fun buildTrustChain() = runTest {
        val trustChainService = TrustChain(
            mockEngine
        )

        val trustChain = trustChainService.resolveTrustChain(
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

        val trustChain2 = trustChainService.resolveTrustChain(
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

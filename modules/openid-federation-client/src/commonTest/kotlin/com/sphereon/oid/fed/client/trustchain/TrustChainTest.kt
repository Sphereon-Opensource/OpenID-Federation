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
    }
}

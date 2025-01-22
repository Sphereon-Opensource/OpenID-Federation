package com.sphereon.oid.fed.client.trustChain

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

object FetchService : IFetchService {
    override suspend fun fetchStatement(endpoint: String): String {
        return mockResponses.find { it[0] == endpoint }?.get(1) ?: throw Exception("Not found")
    }
}

object CryptoService : ICryptoService {
    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return true
    }
}

class TrustChainTest {
    private val client = FederationClient(FetchService, CryptoService)

    @BeforeTest
    fun setupTests() = runTest {
        Logger.configure(Logger.Severity.Debug)
    }

    @Test
    fun buildTrustChain() = runTest {
        val response = client.trustChainResolve(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertFalse(response.error)
        assertEquals(4, response.trustChain?.size)

        assertEquals(
            response.trustChain?.get(0),
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt/.well-known/openid-federation" }
                ?.get(1)
        )

        assertEquals(
            response.trustChain?.get(1),
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/fetch?sub=https://spid.wbss.it/Spid/oidc/rp/ipasv_lt" }
                ?.get(1)
        )

        assertEquals(
            response.trustChain?.get(2),
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa" }
                ?.get(1)
        )

        assertEquals(
            response.trustChain?.get(3),
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )

        val response2 = client.trustChainResolve(
            "https://spid.wbss.it/Spid/oidc/sa",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertFalse(response2.error)
        assertEquals(3, response2.trustChain?.size)
        assertEquals(
            response2.trustChain?.get(0),
            mockResponses.find { it[0] == "https://spid.wbss.it/Spid/oidc/sa/.well-known/openid-federation" }?.get(1)
        )

        assertEquals(
            response2.trustChain?.get(1),
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/fetch?sub=https://spid.wbss.it/Spid/oidc/sa" }
                ?.get(1)
        )

        assertEquals(
            response2.trustChain?.get(2),
            mockResponses.find { it[0] == "https://oidc.registry.servizicie.interno.gov.it/.well-known/openid-federation" }
                ?.get(1)
        )
    }

    @Test
    fun verifyTrustChain() = runTest {
        // First get a valid trust chain
        val resolveResponse = client.trustChainResolve(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertFalse(resolveResponse.error)

        // Now verify the trust chain
        val verifyResponse = client.trustChainVerify(
            resolveResponse.trustChain!!,
            "https://oidc.registry.servizicie.interno.gov.it",
            1728346615
        )

        println(verifyResponse)
        assertEquals(true, verifyResponse.isValid)
        assertEquals(null, verifyResponse.error)

        // Test with empty chain
        val emptyChainResponse = client.trustChainVerify(
            emptyList(),
            "https://oidc.registry.servizicie.interno.gov.it",
            1728346615
        )

        assertEquals(false, emptyChainResponse.isValid)
        assertEquals("Trust chain must contain at least 3 elements", emptyChainResponse.error)

        // Test with wrong trust anchor
        val wrongAnchorResponse = client.trustChainVerify(
            resolveResponse.trustChain ?: emptyList(),
            "https://wrong.trust.anchor",
            1728346615
        )

        assertEquals(false, wrongAnchorResponse.isValid)
        assertEquals("Last statement issuer does not match trust anchor", wrongAnchorResponse.error)
    }

    @Test
    fun verifyTrustChainExpiration() = runTest {
        // First get a valid trust chain
        val resolveResponse = client.trustChainResolve(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

        assertFalse(resolveResponse.error)
        val chain = resolveResponse.trustChain!!

        // Test with current time after expiration
        val futureTime = 1928346615L // Way in the future, after exp
        val expiredResponse = client.trustChainVerify(
            chain,
            "https://oidc.registry.servizicie.interno.gov.it",
            futureTime
        )

        assertEquals(false, expiredResponse.isValid)
        assertEquals("Statement at position 0 has expired", expiredResponse.error)

        // Test with current time before issuance
        val pastTime = 1528346615L // Way in the past, before iat
        val notYetValidResponse = client.trustChainVerify(
            chain,
            "https://oidc.registry.servizicie.interno.gov.it",
            pastTime
        )

        assertEquals(false, notYetValidResponse.isValid)
        assertEquals("Statement at position 0 has invalid iat", notYetValidResponse.error)
    }
}

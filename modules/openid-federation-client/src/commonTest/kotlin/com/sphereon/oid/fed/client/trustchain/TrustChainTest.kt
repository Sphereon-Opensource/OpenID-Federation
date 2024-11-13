package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.FederationClient
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    @Test
    fun buildTrustChain() = runTest {
        val client = FederationClient(FetchService, CryptoService)

        val trustChain = client.resolveTrustChain(
            "https://spid.wbss.it/Spid/oidc/rp/ipasv_lt",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

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

        val trustChain2 = client.resolveTrustChain(
            "https://spid.wbss.it/Spid/oidc/sa",
            arrayOf("https://oidc.registry.servizicie.interno.gov.it")
        )

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

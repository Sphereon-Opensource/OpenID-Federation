package com.sphereon.oid.fed.client.entityConfigurationStatement

import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.trustChain.mockResponses
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

object TestFetchService : IFetchService {
    override suspend fun fetchStatement(endpoint: String): String {
        return mockResponses.find { it[0] == endpoint }?.get(1)
            ?: throw IllegalStateException("Invalid endpoint: $endpoint")
    }
}

object TestCryptoService : ICryptoService {
    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return true
    }
}

class EntityConfigurationStatementTest {
    private val entityConfigurationStatement = EntityConfigurationStatement(TestFetchService, TestCryptoService)

    @BeforeTest
    fun setupTests() = runTest {
        Logger.configure(Logger.Severity.Debug)
    }

    @Test
    fun testGetEntityConfigurationStatement() = runTest {
        val result = entityConfigurationStatement.getEntityConfigurationStatement(
            "https://oidc.registry.servizicie.interno.gov.it"
        )

        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.sub)
        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.iss)
        assertNotNull(result.metadata)
    }

    @Test
    fun testGetFederationEndpoints() = runTest {
        val config = entityConfigurationStatement.getEntityConfigurationStatement(
            "https://oidc.registry.servizicie.interno.gov.it"
        )

        val endpoints = config.getFederationEndpoints()

        assertEquals("https://oidc.registry.servizicie.interno.gov.it/fetch", endpoints.federationFetchEndpoint)
        assertEquals("https://oidc.registry.servizicie.interno.gov.it/resolve", endpoints.federationResolveEndpoint)
        assertEquals(
            "https://oidc.registry.servizicie.interno.gov.it/trust_mark_status",
            endpoints.federationTrustMarkStatusEndpoint
        )
        assertEquals("https://oidc.registry.servizicie.interno.gov.it/list", endpoints.federationListEndpoint)
    }

    @Test
    fun testGetEntityConfigurationStatementInvalidUrl() = runTest {
        assertFailsWith<IllegalStateException> {
            entityConfigurationStatement.getEntityConfigurationStatement("invalid-url")
        }
    }
}
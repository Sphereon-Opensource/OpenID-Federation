package com.sphereon.oid.fed.client.services.entityConfigurationStatementService

import com.sphereon.oid.fed.client.mockResponses.mockResponses
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.IFetchService
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

class EntityConfigurationStatementServiceTest {
    private val entityConfigurationStatementService =
        EntityConfigurationStatementService(TestFetchService, TestCryptoService)

    @BeforeTest
    fun setupTests() = runTest {
        Logger.configure(Logger.Severity.Debug)
    }

    @Test
    fun testGetEntityConfigurationStatement() = runTest {
        val result = entityConfigurationStatementService.getEntityConfigurationStatement(
            "https://oidc.registry.servizicie.interno.gov.it"
        )

        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.sub)
        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.iss)
        assertNotNull(result.metadata)
    }

    @Test
    fun testGetFederationEndpoints() = runTest {
        val config = entityConfigurationStatementService.getEntityConfigurationStatement(
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
            entityConfigurationStatementService.getEntityConfigurationStatement("invalid-url")
        }
    }
}
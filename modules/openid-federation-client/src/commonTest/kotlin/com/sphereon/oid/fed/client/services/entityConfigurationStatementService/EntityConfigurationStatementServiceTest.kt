package com.sphereon.oid.fed.client.services.entityConfigurationStatementService

import com.sphereon.oid.fed.client.context.FederationContext
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
    private val context = FederationContext(
        fetchService = TestFetchService,
        cryptoService = TestCryptoService
    )
    private val entityConfigurationStatementService = EntityConfigurationStatementService(context)

    @BeforeTest
    fun setupTests() = runTest {
        Logger.configure(Logger.Severity.Debug)
    }

    @Test
    fun testFetchEntityConfigurationStatement() = runTest {
        val result = entityConfigurationStatementService.fetchEntityConfigurationStatement(
            "https://oidc.registry.servizicie.interno.gov.it"
        )

        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.sub)
        assertEquals("https://oidc.registry.servizicie.interno.gov.it", result.iss)
        assertNotNull(result.metadata)
    }

    @Test
    fun testGetFederationEndpoints() = runTest {
        val config = entityConfigurationStatementService.fetchEntityConfigurationStatement(
            "https://oidc.registry.servizicie.interno.gov.it"
        )

        val endpoints = entityConfigurationStatementService.getFederationEndpoints(config)

        assertEquals("https://oidc.registry.servizicie.interno.gov.it/fetch", endpoints.federationFetchEndpoint)
        assertEquals("https://oidc.registry.servizicie.interno.gov.it/resolve", endpoints.federationResolveEndpoint)
        assertEquals(
            "https://oidc.registry.servizicie.interno.gov.it/trust_mark_status",
            endpoints.federationTrustMarkStatusEndpoint
        )
        assertEquals("https://oidc.registry.servizicie.interno.gov.it/list", endpoints.federationListEndpoint)
    }

    @Test
    fun testFetchEntityConfigurationStatementInvalidUrl() = runTest {
        assertFailsWith<IllegalStateException> {
            entityConfigurationStatementService.fetchEntityConfigurationStatement("invalid-url")
        }
    }
}
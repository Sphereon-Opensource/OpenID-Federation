package com.sphereon.oid.fed.client.services.entityConfigurationStatementService

import com.sphereon.oid.fed.cache.InMemoryCache
import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.mapper.InvalidJwtException
import com.sphereon.oid.fed.client.mockResponses.mockResponses
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Jwk
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

object TestCryptoService : ICryptoService {
    override suspend fun verify(jwt: String, key: Jwk): Boolean {
        return true
    }
}

class EntityConfigurationStatementServiceTest {
    private val mockEngine = MockEngine { request ->
        val endpoint = request.url.toString()
        val response = mockResponses.find { it[0] == endpoint }?.get(1)
        if (response != null) {
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        } else {
            respond(
                content = "Not found",
                status = HttpStatusCode.NotFound
            )
        }
    }
    private val httpClient = HttpClient(mockEngine) {
        install(HttpTimeout) {
            requestTimeoutMillis = 5.seconds.inWholeMilliseconds
            connectTimeoutMillis = 5.seconds.inWholeMilliseconds
            socketTimeoutMillis = 5.seconds.inWholeMilliseconds
        }
    }
    private val context = FederationContext.create(
        cryptoService = TestCryptoService,
        cache = InMemoryCache(),
        httpClient = httpClient
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
        assertFailsWith<InvalidJwtException> {
            entityConfigurationStatementService.fetchEntityConfigurationStatement("invalid-url")
        }
    }
}
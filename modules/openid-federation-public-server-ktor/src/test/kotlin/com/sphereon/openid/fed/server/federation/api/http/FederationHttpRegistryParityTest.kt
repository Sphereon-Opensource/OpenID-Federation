package com.sphereon.openid.fed.server.federation.api.http

import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommandRegistry
import com.sphereon.core.api.http.dispatch.HttpAdapterCatalog
import com.sphereon.core.api.http.dispatch.HttpAdapterDispatcher
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.server.federation.api.http.command.*
import com.sphereon.openid.fed.server.federation.ktor.di.createFederationServerAppGraph
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FederationHttpRegistryParityTest {
    @Test
    fun everyDescribedFederationHandlerIsRegisteredInTheRealSessionGraph() = runTest {
        val app = createFederationServerAppGraph(Any())
        try {
            val session = app.userContextManager.getAnonymous().sessionContextManager
                .createOrGetFromId("federation-http-registry-parity", principalType = PrincipalType.USER)
            val registry = (session.graph as HttpEndpointCommandRegistry.Graph).httpEndpointCommandRegistry
            val catalog = (session.graph as HttpAdapterCatalog.Graph).httpAdapterCatalog
            val endpoints = assertNotNull(catalog.descriptionById(FederationHttpAdapter.ID)).endpoints
            val handlerIds = endpoints.map { assertNotNull(it.handlerCommandId) }.toSet()

            val expected = listOf(
                GetEntityConfigurationEndpointCommand.COMMAND_ID to GetEntityConfigurationEndpointCommand.ENDPOINT,
                GetAccountEntityConfigurationEndpointCommand.COMMAND_ID to GetAccountEntityConfigurationEndpointCommand.ENDPOINT,
                ListSubordinatesRootEndpointCommand.COMMAND_ID to ListSubordinatesRootEndpointCommand.ENDPOINT,
                PostListSubordinatesRootEndpointCommand.COMMAND_ID to PostListSubordinatesRootEndpointCommand.ENDPOINT,
                ListSubordinatesAccountEndpointCommand.COMMAND_ID to ListSubordinatesAccountEndpointCommand.ENDPOINT,
                PostListSubordinatesAccountEndpointCommand.COMMAND_ID to PostListSubordinatesAccountEndpointCommand.ENDPOINT,
                FetchSubordinateRootEndpointCommand.COMMAND_ID to FetchSubordinateRootEndpointCommand.ENDPOINT,
                PostFetchSubordinateRootEndpointCommand.COMMAND_ID to PostFetchSubordinateRootEndpointCommand.ENDPOINT,
                FetchSubordinateAccountEndpointCommand.COMMAND_ID to FetchSubordinateAccountEndpointCommand.ENDPOINT,
                PostFetchSubordinateAccountEndpointCommand.COMMAND_ID to PostFetchSubordinateAccountEndpointCommand.ENDPOINT,
                GetTrustMarkStatusRootEndpointCommand.COMMAND_ID to GetTrustMarkStatusRootEndpointCommand.ENDPOINT,
                TrustMarkStatusRootEndpointCommand.COMMAND_ID to TrustMarkStatusRootEndpointCommand.ENDPOINT,
                GetTrustMarkStatusAccountEndpointCommand.COMMAND_ID to GetTrustMarkStatusAccountEndpointCommand.ENDPOINT,
                TrustMarkStatusAccountEndpointCommand.COMMAND_ID to TrustMarkStatusAccountEndpointCommand.ENDPOINT,
                TrustMarkListRootEndpointCommand.COMMAND_ID to TrustMarkListRootEndpointCommand.ENDPOINT,
                PostTrustMarkListRootEndpointCommand.COMMAND_ID to PostTrustMarkListRootEndpointCommand.ENDPOINT,
                TrustMarkListAccountEndpointCommand.COMMAND_ID to TrustMarkListAccountEndpointCommand.ENDPOINT,
                PostTrustMarkListAccountEndpointCommand.COMMAND_ID to PostTrustMarkListAccountEndpointCommand.ENDPOINT,
                GetTrustMarkRootEndpointCommand.COMMAND_ID to GetTrustMarkRootEndpointCommand.ENDPOINT,
                PostGetTrustMarkRootEndpointCommand.COMMAND_ID to PostGetTrustMarkRootEndpointCommand.ENDPOINT,
                GetTrustMarkAccountEndpointCommand.COMMAND_ID to GetTrustMarkAccountEndpointCommand.ENDPOINT,
                PostGetTrustMarkAccountEndpointCommand.COMMAND_ID to PostGetTrustMarkAccountEndpointCommand.ENDPOINT,
                HistoricalKeysRootEndpointCommand.COMMAND_ID to HistoricalKeysRootEndpointCommand.ENDPOINT,
                HistoricalKeysAccountEndpointCommand.COMMAND_ID to HistoricalKeysAccountEndpointCommand.ENDPOINT,
                ResolveRootEndpointCommand.COMMAND_ID to ResolveRootEndpointCommand.ENDPOINT,
                PostResolveRootEndpointCommand.COMMAND_ID to PostResolveRootEndpointCommand.ENDPOINT,
                ResolveAccountEndpointCommand.COMMAND_ID to ResolveAccountEndpointCommand.ENDPOINT,
                PostResolveAccountEndpointCommand.COMMAND_ID to PostResolveAccountEndpointCommand.ENDPOINT,
            )
            val routesByHandlerId = endpoints.associateBy { assertNotNull(it.handlerCommandId) }

            assertEquals(endpoints.size, routesByHandlerId.size)
            assertEquals(expected.map { it.first }.toSet(), handlerIds)
            assertTrue(registry.listHandlerCommandIds().containsAll(handlerIds))
            expected.forEach { (handlerId, handler) ->
                val selectedRoute = assertNotNull(routesByHandlerId[handlerId])
                assertEquals(handler.method, selectedRoute.method, handlerId)
                assertEquals(handler.pathPatterns, selectedRoute.pathPatterns, handlerId)
            }

            // The canonical dispatcher receives the real lazy adapter map; materializing
            // every endpoint command here would initialize the configured JDBC store.
            val dispatcher = (session.graph as HttpAdapterDispatcher.Graph).httpAdapterDispatcher
            val adapterProviders = dispatcher.javaClass.getDeclaredField("adapters").apply {
                isAccessible = true
            }.get(dispatcher) as Map<*, *>
            val adapter = assertNotNull(adapterProviders[FederationHttpAdapter.ID] as? Lazy<*>).value as HttpAdapter
            assertEquals(FederationHttpAdapter.ID, adapter.id)
        } finally {
            app.destroy()
        }
    }
}

package com.sphereon.openid.fed.server.admin.ktor

import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommandRegistry
import com.sphereon.core.api.http.dispatch.HttpAdapterCatalog
import com.sphereon.core.api.http.dispatch.HttpAdapterDispatcher
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand
import com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand
import com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.server.admin.api.http.AdminAccountDescriptorContribution
import com.sphereon.openid.fed.server.admin.api.http.AdminHttpAdapter
import com.sphereon.openid.fed.server.admin.api.http.command.*
import com.sphereon.openid.fed.server.admin.api.http.describe.AdminHttpAdapterDescriptorProvider
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminHttpRegistryParityTest {
    @Test
    fun selectedTrustMarkDeleteRouteMatchesItsEndpointHandlerPattern() {
        val app = createAdminServerAppGraph(Any())
        try {
            val selectedRoute = AdminHttpAdapterDescriptorProvider(
                app.configBinder,
                emptySet(),
            ).describe().endpoints.single {
                it.handlerCommandId == DeleteTrustMarkEndpointCommand.COMMAND_ID
            }
            val handler = DeleteTrustMarkEndpointCommand.ENDPOINT

            assertEquals(DeleteTrustMarkCommand.COMMAND_ID, selectedRoute.commandId)
            assertEquals(handler.method, selectedRoute.method)
            assertEquals(handler.pathPatterns, selectedRoute.pathPatterns)
        } finally {
            app.destroy()
        }
    }

    @Test
    fun everyDescribedAdminHandlerIsRegisteredInTheRealSessionGraph() = runTest {
        val app = createAdminServerAppGraph(Any())
        try {
            val session = app.userContextManager.getAnonymous().sessionContextManager
                .createOrGetFromId("admin-http-registry-parity", principalType = PrincipalType.USER)
            val registry = (session.graph as HttpEndpointCommandRegistry.Graph).httpEndpointCommandRegistry
            val catalog = (session.graph as HttpAdapterCatalog.Graph).httpAdapterCatalog
            val endpoints = assertNotNull(catalog.descriptionById(AdminHttpAdapter.ID)).endpoints
            val handlerIds = endpoints.map { assertNotNull(it.handlerCommandId) }.toSet()

            val expected = listOf(
                ListKeysEndpointCommand.COMMAND_ID to ListKeysEndpointCommand.ENDPOINT,
                CreateKeyEndpointCommand.COMMAND_ID to CreateKeyEndpointCommand.ENDPOINT,
                RevokeKeyEndpointCommand.COMMAND_ID to RevokeKeyEndpointCommand.ENDPOINT,
                GetSigningKeySelectionEndpointCommand.COMMAND_ID to GetSigningKeySelectionEndpointCommand.ENDPOINT,
                SetSigningKeySelectionEndpointCommand.COMMAND_ID to SetSigningKeySelectionEndpointCommand.ENDPOINT,
                ListSubordinatesEndpointCommand.COMMAND_ID to ListSubordinatesEndpointCommand.ENDPOINT,
                CreateSubordinateEndpointCommand.COMMAND_ID to CreateSubordinateEndpointCommand.ENDPOINT,
                DeleteSubordinateEndpointCommand.COMMAND_ID to DeleteSubordinateEndpointCommand.ENDPOINT,
                ListSubordinateKeysEndpointCommand.COMMAND_ID to ListSubordinateKeysEndpointCommand.ENDPOINT,
                CreateSubordinateKeyEndpointCommand.COMMAND_ID to CreateSubordinateKeyEndpointCommand.ENDPOINT,
                DeleteSubordinateKeyEndpointCommand.COMMAND_ID to DeleteSubordinateKeyEndpointCommand.ENDPOINT,
                GetSubordinateStatementEndpointCommand.COMMAND_ID to GetSubordinateStatementEndpointCommand.ENDPOINT,
                PublishSubordinateStatementEndpointCommand.COMMAND_ID to PublishSubordinateStatementEndpointCommand.ENDPOINT,
                ListSubordinateMetadataEndpointCommand.COMMAND_ID to ListSubordinateMetadataEndpointCommand.ENDPOINT,
                CreateSubordinateMetadataEndpointCommand.COMMAND_ID to CreateSubordinateMetadataEndpointCommand.ENDPOINT,
                DeleteSubordinateMetadataEndpointCommand.COMMAND_ID to DeleteSubordinateMetadataEndpointCommand.ENDPOINT,
                ListTrustMarksEndpointCommand.COMMAND_ID to ListTrustMarksEndpointCommand.ENDPOINT,
                CreateTrustMarkEndpointCommand.COMMAND_ID to CreateTrustMarkEndpointCommand.ENDPOINT,
                DeleteTrustMarkEndpointCommand.COMMAND_ID to DeleteTrustMarkEndpointCommand.ENDPOINT,
                ListTrustMarkTypesEndpointCommand.COMMAND_ID to ListTrustMarkTypesEndpointCommand.ENDPOINT,
                CreateTrustMarkTypeEndpointCommand.COMMAND_ID to CreateTrustMarkTypeEndpointCommand.ENDPOINT,
                GetTrustMarkTypeEndpointCommand.COMMAND_ID to GetTrustMarkTypeEndpointCommand.ENDPOINT,
                DeleteTrustMarkTypeEndpointCommand.COMMAND_ID to DeleteTrustMarkTypeEndpointCommand.ENDPOINT,
                GetTrustMarkTypeIssuersEndpointCommand.COMMAND_ID to GetTrustMarkTypeIssuersEndpointCommand.ENDPOINT,
                AddTrustMarkTypeIssuerEndpointCommand.COMMAND_ID to AddTrustMarkTypeIssuerEndpointCommand.ENDPOINT,
                RemoveTrustMarkTypeIssuerEndpointCommand.COMMAND_ID to RemoveTrustMarkTypeIssuerEndpointCommand.ENDPOINT,
                GetEntityStatementEndpointCommand.COMMAND_ID to GetEntityStatementEndpointCommand.ENDPOINT,
                PublishEntityStatementEndpointCommand.COMMAND_ID to PublishEntityStatementEndpointCommand.ENDPOINT,
                ListMetadataEndpointCommand.COMMAND_ID to ListMetadataEndpointCommand.ENDPOINT,
                CreateMetadataEndpointCommand.COMMAND_ID to CreateMetadataEndpointCommand.ENDPOINT,
                DeleteMetadataEndpointCommand.COMMAND_ID to DeleteMetadataEndpointCommand.ENDPOINT,
                ListAuthorityHintsEndpointCommand.COMMAND_ID to ListAuthorityHintsEndpointCommand.ENDPOINT,
                CreateAuthorityHintEndpointCommand.COMMAND_ID to CreateAuthorityHintEndpointCommand.ENDPOINT,
                DeleteAuthorityHintEndpointCommand.COMMAND_ID to DeleteAuthorityHintEndpointCommand.ENDPOINT,
                ListTrustAnchorHintsEndpointCommand.COMMAND_ID to ListTrustAnchorHintsEndpointCommand.ENDPOINT,
                CreateTrustAnchorHintEndpointCommand.COMMAND_ID to CreateTrustAnchorHintEndpointCommand.ENDPOINT,
                DeleteTrustAnchorHintEndpointCommand.COMMAND_ID to DeleteTrustAnchorHintEndpointCommand.ENDPOINT,
                ListCriticalClaimsEndpointCommand.COMMAND_ID to ListCriticalClaimsEndpointCommand.ENDPOINT,
                CreateCriticalClaimEndpointCommand.COMMAND_ID to CreateCriticalClaimEndpointCommand.ENDPOINT,
                DeleteCriticalClaimEndpointCommand.COMMAND_ID to DeleteCriticalClaimEndpointCommand.ENDPOINT,
                ListMetadataPoliciesEndpointCommand.COMMAND_ID to ListMetadataPoliciesEndpointCommand.ENDPOINT,
                CreateMetadataPolicyEndpointCommand.COMMAND_ID to CreateMetadataPolicyEndpointCommand.ENDPOINT,
                DeleteMetadataPolicyEndpointCommand.COMMAND_ID to DeleteMetadataPolicyEndpointCommand.ENDPOINT,
                ListReceivedTrustMarksEndpointCommand.COMMAND_ID to ListReceivedTrustMarksEndpointCommand.ENDPOINT,
                CreateReceivedTrustMarkEndpointCommand.COMMAND_ID to CreateReceivedTrustMarkEndpointCommand.ENDPOINT,
                DeleteReceivedTrustMarkEndpointCommand.COMMAND_ID to DeleteReceivedTrustMarkEndpointCommand.ENDPOINT,
                GetSubordinateConstraintsEndpointCommand.COMMAND_ID to GetSubordinateConstraintsEndpointCommand.ENDPOINT,
                SetSubordinateConstraintsEndpointCommand.COMMAND_ID to SetSubordinateConstraintsEndpointCommand.ENDPOINT,
                DeleteSubordinateConstraintsEndpointCommand.COMMAND_ID to DeleteSubordinateConstraintsEndpointCommand.ENDPOINT,
                ListLogsEndpointCommand.COMMAND_ID to ListLogsEndpointCommand.ENDPOINT,
                GetCacheStatsEndpointCommand.COMMAND_ID to GetCacheStatsEndpointCommand.ENDPOINT,
                ClearCacheEndpointCommand.COMMAND_ID to ClearCacheEndpointCommand.ENDPOINT,
            ) + if (app.configBinder.getIdentityConfig().isAccount) {
                realAccountContribution()?.endpointDescriptors.orEmpty().map { descriptor ->
                    assertNotNull(descriptor.handlerCommandId) to descriptor
                }
            } else {
                emptyList()
            }
            val routesByHandlerId = endpoints.associateBy { assertNotNull(it.handlerCommandId) }

            assertEquals(endpoints.size, routesByHandlerId.size)
            assertEquals(expected.map { it.first }.toSet(), handlerIds)
            assertTrue(registry.listHandlerCommandIds().containsAll(handlerIds))
            expected.forEach { (handlerId, handler) ->
                val selectedRoute = assertNotNull(routesByHandlerId[handlerId])
                assertEquals(handler.method, selectedRoute.method, handlerId)
                assertEquals(handler.pathPatterns, selectedRoute.pathPatterns, handlerId)
            }

            // Resolve only a cache-backed command; resolving every registered handler here
            // would construct persistence-backed services and open the configured JDBC store.
            assertEquals(
                GetCacheStatsEndpointCommand.COMMAND_ID,
                registry.get(GetCacheStatsEndpointCommand.COMMAND_ID)?.id,
            )
            val dispatcher = (session.graph as HttpAdapterDispatcher.Graph).httpAdapterDispatcher
            val adapterProviders = dispatcher.javaClass.getDeclaredField("adapters").apply {
                isAccessible = true
            }.get(dispatcher) as Map<*, *>
            val adapter = assertNotNull(adapterProviders[AdminHttpAdapter.ID] as? Lazy<*>).value as HttpAdapter
            assertEquals(AdminHttpAdapter.ID, adapter.id)
        } finally {
            app.destroy()
        }
    }

    @Test
    fun legacyAccountDescriptorsRemainAbsentWhenIdentityModeIsExternal() {
        val app = createAdminServerAppGraph(Any())
        try {
            val contribution = realAccountContribution()
            val accountHandlerIds = setOf(
                ListAccountsEndpointCommand.COMMAND_ID,
                CreateAccountEndpointCommand.COMMAND_ID,
                DeleteAccountEndpointCommand.COMMAND_ID,
            )
            val accountBinder = object : OidfConfigBinder by app.configBinder {
                override fun getIdentityConfig() = app.configBinder.getIdentityConfig().copy(mode = IdentityMode.ACCOUNT)
            }
            val externalBinder = object : OidfConfigBinder by app.configBinder {
                override fun getIdentityConfig() = app.configBinder.getIdentityConfig().copy(mode = IdentityMode.EXTERNAL)
            }

            val omittedRoutes = AdminHttpAdapterDescriptorProvider(accountBinder, emptySet()).describe().endpoints
            assertFalse(omittedRoutes.any { it.handlerCommandId in accountHandlerIds })
            if (contribution == null) return

            assertEquals(accountHandlerIds, contribution.endpointDescriptors.map { it.handlerCommandId }.toSet())
            val accountRoutes = AdminHttpAdapterDescriptorProvider(accountBinder, setOf(contribution)).describe().endpoints
            val externalRoutes = AdminHttpAdapterDescriptorProvider(externalBinder, setOf(contribution)).describe().endpoints

            assertEquals(accountHandlerIds, accountRoutes.mapNotNull { it.handlerCommandId }.toSet().intersect(accountHandlerIds))
            assertFalse(externalRoutes.any { it.handlerCommandId in accountHandlerIds })
            contribution.endpointDescriptors.forEach { expected ->
                val actual = assertNotNull(accountRoutes.singleOrNull { it.handlerCommandId == expected.handlerCommandId })
                assertEquals(expected.method, actual.method)
                assertEquals(expected.pathPatterns, actual.pathPatterns)
            }
        } finally {
            app.destroy()
        }
    }

    private fun realAccountContribution(): AdminAccountDescriptorContribution? = try {
        Class.forName("com.sphereon.openid.fed.account.http.di.AccountAdminDescriptorContributionImpl")
            .getDeclaredConstructor().newInstance() as AdminAccountDescriptorContribution
    } catch (_: ClassNotFoundException) {
        // The account-http implementation is optional in platform packaging.
        null
    }
}

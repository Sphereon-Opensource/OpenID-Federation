package com.sphereon.openid.fed.server.admin.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.StaticPublicApiDescriptor
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.server.admin.api.http.AdminAccountDescriptorContribution
import com.sphereon.openid.fed.server.admin.api.http.AdminHttpAdapter
import com.sphereon.openid.fed.server.admin.api.http.command.*
import com.sphereon.openid.fed.services.command.authorityHint.CreateAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.DeleteAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.FindAuthorityHintsByAccountCommand
import com.sphereon.openid.fed.services.command.trustAnchorHint.CreateTrustAnchorHintCommand
import com.sphereon.openid.fed.services.command.trustAnchorHint.DeleteTrustAnchorHintCommand
import com.sphereon.openid.fed.services.command.trustAnchorHint.FindTrustAnchorHintsByAccountCommand
import com.sphereon.openid.fed.services.command.subordinateConstraint.GetSubordinateConstraintsCommand
import com.sphereon.openid.fed.services.command.subordinateConstraint.SetSubordinateConstraintsCommand
import com.sphereon.openid.fed.services.command.subordinateConstraint.DeleteSubordinateConstraintsCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
import com.sphereon.openid.fed.services.command.metadata.CreateMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.DeleteMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.FindMetadataByAccountCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksCommand
import com.sphereon.openid.fed.services.command.trustMark.AddIssuerToTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkCommand
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkCommand
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.FindAllTrustMarkTypesByAccountCommand
import com.sphereon.openid.fed.services.command.trustMark.FindTrustMarkTypeByIdCommand
import com.sphereon.openid.fed.services.command.trustMark.GetIssuersForTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarksForAccountCommand
import com.sphereon.openid.fed.services.command.trustMark.RemoveIssuerFromTrustMarkTypeCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * AppScope descriptor provider for AdminHttpAdapter.
 *
 * Uses StaticPublicApiDescriptor to provide endpoint metadata from service command
 * ENDPOINT descriptors (single source of truth). Non-1:1 endpoints (cache, logs)
 * reference their own endpoint command descriptors.
 *
 * Account management endpoints are included only when:
 * - LEGACY identity mode, and
 * - [AdminAccountDescriptorContribution] is present (account-http on classpath).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<HttpAdapterDescriptorProvider>())
class AdminHttpAdapterDescriptorProvider(
    configBinder: OidfConfigBinder,
    accountDescriptors: Set<AdminAccountDescriptorContribution>,
) : StaticPublicApiDescriptor(
    adapterId = AdminHttpAdapter.ID,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    ),
    endpoints = buildAdminEndpoints(configBinder, accountDescriptors)
)

private fun buildAdminEndpoints(
    configBinder: OidfConfigBinder,
    accountDescriptors: Set<AdminAccountDescriptorContribution>,
): List<HttpEndpointDescriptor> = buildList {
        if (configBinder.getIdentityConfig().isAccount) {
        accountDescriptors.forEach { addAll(it.endpointDescriptors) }
    }
    addAll(com.sphereon.openid.fed.admin.AdminOperation.entries.map(::standardAdminEndpoint))
    addAll(listOf(
        ListLogsEndpointCommand.ENDPOINT.copy(handlerCommandId = ListLogsEndpointCommand.COMMAND_ID),
        GetCacheStatsEndpointCommand.ENDPOINT.copy(handlerCommandId = GetCacheStatsEndpointCommand.COMMAND_ID),
        ClearCacheEndpointCommand.ENDPOINT.copy(handlerCommandId = ClearCacheEndpointCommand.COMMAND_ID),
    ))
}

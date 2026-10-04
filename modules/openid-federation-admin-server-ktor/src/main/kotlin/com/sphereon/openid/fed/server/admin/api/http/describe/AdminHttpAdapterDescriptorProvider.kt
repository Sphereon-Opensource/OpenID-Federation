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
    addAll(
        listOf(
        // Key endpoints (from endpoint commands)
        ListKeysEndpointCommand.ENDPOINT.copy(handlerCommandId = ListKeysEndpointCommand.COMMAND_ID),
        CreateKeyEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateKeyEndpointCommand.COMMAND_ID),
        RevokeKeyEndpointCommand.ENDPOINT.copy(handlerCommandId = RevokeKeyEndpointCommand.COMMAND_ID),
        // Subordinate endpoints (from endpoint commands)
        ListSubordinatesEndpointCommand.ENDPOINT.copy(handlerCommandId = ListSubordinatesEndpointCommand.COMMAND_ID),
        CreateSubordinateEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateSubordinateEndpointCommand.COMMAND_ID),
        DeleteSubordinateEndpointCommand.ENDPOINT.copy(handlerCommandId = DeleteSubordinateEndpointCommand.COMMAND_ID),
        ListSubordinateKeysEndpointCommand.ENDPOINT.copy(handlerCommandId = ListSubordinateKeysEndpointCommand.COMMAND_ID),
        CreateSubordinateKeyEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateSubordinateKeyEndpointCommand.COMMAND_ID),
        DeleteSubordinateKeyEndpointCommand.ENDPOINT.copy(handlerCommandId = DeleteSubordinateKeyEndpointCommand.COMMAND_ID),
        GetSubordinateStatementEndpointCommand.ENDPOINT.copy(handlerCommandId = GetSubordinateStatementEndpointCommand.COMMAND_ID),
        PublishSubordinateStatementEndpointCommand.ENDPOINT.copy(handlerCommandId = PublishSubordinateStatementEndpointCommand.COMMAND_ID),
        ListSubordinateMetadataEndpointCommand.ENDPOINT.copy(handlerCommandId = ListSubordinateMetadataEndpointCommand.COMMAND_ID),
        CreateSubordinateMetadataEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateSubordinateMetadataEndpointCommand.COMMAND_ID),
        DeleteSubordinateMetadataEndpointCommand.ENDPOINT.copy(handlerCommandId = DeleteSubordinateMetadataEndpointCommand.COMMAND_ID),
        // Trust mark endpoints (from service commands)
        GetTrustMarksForAccountCommand.ENDPOINT.copy(handlerCommandId = ListTrustMarksEndpointCommand.COMMAND_ID),
        CreateTrustMarkCommand.ENDPOINT.copy(handlerCommandId = CreateTrustMarkEndpointCommand.COMMAND_ID),
        DeleteTrustMarkCommand.ENDPOINT.copy(
            pathPatterns = DeleteTrustMarkEndpointCommand.ENDPOINT.pathPatterns,
            handlerCommandId = DeleteTrustMarkEndpointCommand.COMMAND_ID,
        ),
        // Trust mark type endpoints (from service commands)
        FindAllTrustMarkTypesByAccountCommand.ENDPOINT.copy(handlerCommandId = ListTrustMarkTypesEndpointCommand.COMMAND_ID),
        CreateTrustMarkTypeCommand.ENDPOINT.copy(handlerCommandId = CreateTrustMarkTypeEndpointCommand.COMMAND_ID),
        FindTrustMarkTypeByIdCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkTypeEndpointCommand.COMMAND_ID),
        DeleteTrustMarkTypeCommand.ENDPOINT.copy(handlerCommandId = DeleteTrustMarkTypeEndpointCommand.COMMAND_ID),
        GetIssuersForTrustMarkTypeCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkTypeIssuersEndpointCommand.COMMAND_ID),
        AddIssuerToTrustMarkTypeCommand.ENDPOINT.copy(handlerCommandId = AddTrustMarkTypeIssuerEndpointCommand.COMMAND_ID),
        RemoveIssuerFromTrustMarkTypeCommand.ENDPOINT.copy(handlerCommandId = RemoveTrustMarkTypeIssuerEndpointCommand.COMMAND_ID),
        // Entity statement endpoints (from service commands)
        FindEntityConfigurationByAccountCommand.ENDPOINT.copy(handlerCommandId = GetEntityStatementEndpointCommand.COMMAND_ID),
        PublishEntityConfigurationCommand.ENDPOINT.copy(handlerCommandId = PublishEntityStatementEndpointCommand.COMMAND_ID),
        // Metadata endpoints (from service commands)
        FindMetadataByAccountCommand.ENDPOINT.copy(handlerCommandId = ListMetadataEndpointCommand.COMMAND_ID),
        CreateMetadataCommand.ENDPOINT.copy(handlerCommandId = CreateMetadataEndpointCommand.COMMAND_ID),
        DeleteMetadataCommand.ENDPOINT.copy(handlerCommandId = DeleteMetadataEndpointCommand.COMMAND_ID),
        // Authority hint endpoints (from service commands)
        FindAuthorityHintsByAccountCommand.ENDPOINT.copy(handlerCommandId = ListAuthorityHintsEndpointCommand.COMMAND_ID),
        CreateAuthorityHintCommand.ENDPOINT.copy(handlerCommandId = CreateAuthorityHintEndpointCommand.COMMAND_ID),
        DeleteAuthorityHintCommand.ENDPOINT.copy(handlerCommandId = DeleteAuthorityHintEndpointCommand.COMMAND_ID),
        // Trust anchor hint endpoints (from service commands)
        FindTrustAnchorHintsByAccountCommand.ENDPOINT.copy(handlerCommandId = ListTrustAnchorHintsEndpointCommand.COMMAND_ID),
        CreateTrustAnchorHintCommand.ENDPOINT.copy(handlerCommandId = CreateTrustAnchorHintEndpointCommand.COMMAND_ID),
        DeleteTrustAnchorHintCommand.ENDPOINT.copy(handlerCommandId = DeleteTrustAnchorHintEndpointCommand.COMMAND_ID),
        // Critical claim endpoints (from endpoint commands)
        ListCriticalClaimsEndpointCommand.ENDPOINT.copy(handlerCommandId = ListCriticalClaimsEndpointCommand.COMMAND_ID),
        CreateCriticalClaimEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateCriticalClaimEndpointCommand.COMMAND_ID),
        DeleteCriticalClaimEndpointCommand.ENDPOINT.copy(handlerCommandId = DeleteCriticalClaimEndpointCommand.COMMAND_ID),
        // Metadata policy endpoints (from endpoint commands)
        ListMetadataPoliciesEndpointCommand.ENDPOINT.copy(handlerCommandId = ListMetadataPoliciesEndpointCommand.COMMAND_ID),
        CreateMetadataPolicyEndpointCommand.ENDPOINT.copy(handlerCommandId = CreateMetadataPolicyEndpointCommand.COMMAND_ID),
        DeleteMetadataPolicyEndpointCommand.ENDPOINT.copy(handlerCommandId = DeleteMetadataPolicyEndpointCommand.COMMAND_ID),
        // Received trust mark endpoints (from service commands)
        ListReceivedTrustMarksCommand.ENDPOINT.copy(handlerCommandId = ListReceivedTrustMarksEndpointCommand.COMMAND_ID),
        CreateReceivedTrustMarkCommand.ENDPOINT.copy(handlerCommandId = CreateReceivedTrustMarkEndpointCommand.COMMAND_ID),
        DeleteReceivedTrustMarkCommand.ENDPOINT.copy(handlerCommandId = DeleteReceivedTrustMarkEndpointCommand.COMMAND_ID),
        // Subordinate constraint endpoints (from service commands)
        GetSubordinateConstraintsCommand.ENDPOINT.copy(handlerCommandId = GetSubordinateConstraintsEndpointCommand.COMMAND_ID),
        SetSubordinateConstraintsCommand.ENDPOINT.copy(handlerCommandId = SetSubordinateConstraintsEndpointCommand.COMMAND_ID),
        DeleteSubordinateConstraintsCommand.ENDPOINT.copy(handlerCommandId = DeleteSubordinateConstraintsEndpointCommand.COMMAND_ID),
        // Log endpoints (from endpoint commands)
        ListLogsEndpointCommand.ENDPOINT.copy(handlerCommandId = ListLogsEndpointCommand.COMMAND_ID),
        // Cache endpoints (from endpoint commands)
        GetCacheStatsEndpointCommand.ENDPOINT.copy(handlerCommandId = GetCacheStatsEndpointCommand.COMMAND_ID),
        ClearCacheEndpointCommand.ENDPOINT.copy(handlerCommandId = ClearCacheEndpointCommand.COMMAND_ID)
        )
    )
}

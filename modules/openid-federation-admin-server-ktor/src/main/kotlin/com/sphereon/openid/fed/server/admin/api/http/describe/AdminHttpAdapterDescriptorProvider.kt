package com.sphereon.openid.fed.server.admin.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.StaticPublicApiDescriptor
import com.sphereon.openid.fed.server.admin.api.http.AdminHttpAdapter
import com.sphereon.openid.fed.server.admin.api.http.command.*
import com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand
import com.sphereon.openid.fed.account.http.command.CreateAccountEndpointCommand
import com.sphereon.openid.fed.account.http.command.DeleteAccountEndpointCommand
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
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<HttpAdapterDescriptorProvider>())
class AdminHttpAdapterDescriptorProvider : StaticPublicApiDescriptor(
    adapterId = AdminHttpAdapter.ID,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    ),
    endpoints = listOf(
        // Account endpoints (from endpoint commands)
        ListAccountsEndpointCommand.ENDPOINT,
        CreateAccountEndpointCommand.ENDPOINT,
        DeleteAccountEndpointCommand.ENDPOINT,
        // Key endpoints (from endpoint commands)
        ListKeysEndpointCommand.ENDPOINT,
        CreateKeyEndpointCommand.ENDPOINT,
        RevokeKeyEndpointCommand.ENDPOINT,
        // Subordinate endpoints (from endpoint commands)
        ListSubordinatesEndpointCommand.ENDPOINT,
        CreateSubordinateEndpointCommand.ENDPOINT,
        DeleteSubordinateEndpointCommand.ENDPOINT,
        ListSubordinateKeysEndpointCommand.ENDPOINT,
        CreateSubordinateKeyEndpointCommand.ENDPOINT,
        DeleteSubordinateKeyEndpointCommand.ENDPOINT,
        GetSubordinateStatementEndpointCommand.ENDPOINT,
        PublishSubordinateStatementEndpointCommand.ENDPOINT,
        ListSubordinateMetadataEndpointCommand.ENDPOINT,
        CreateSubordinateMetadataEndpointCommand.ENDPOINT,
        DeleteSubordinateMetadataEndpointCommand.ENDPOINT,
        // Trust mark endpoints (from service commands)
        GetTrustMarksForAccountCommand.ENDPOINT,
        CreateTrustMarkCommand.ENDPOINT,
        DeleteTrustMarkCommand.ENDPOINT,
        // Trust mark type endpoints (from service commands)
        FindAllTrustMarkTypesByAccountCommand.ENDPOINT,
        CreateTrustMarkTypeCommand.ENDPOINT,
        FindTrustMarkTypeByIdCommand.ENDPOINT,
        DeleteTrustMarkTypeCommand.ENDPOINT,
        GetIssuersForTrustMarkTypeCommand.ENDPOINT,
        AddIssuerToTrustMarkTypeCommand.ENDPOINT,
        RemoveIssuerFromTrustMarkTypeCommand.ENDPOINT,
        // Entity statement endpoints (from service commands)
        FindEntityConfigurationByAccountCommand.ENDPOINT,
        PublishEntityConfigurationCommand.ENDPOINT,
        // Metadata endpoints (from service commands)
        FindMetadataByAccountCommand.ENDPOINT,
        CreateMetadataCommand.ENDPOINT,
        DeleteMetadataCommand.ENDPOINT,
        // Authority hint endpoints (from service commands)
        FindAuthorityHintsByAccountCommand.ENDPOINT,
        CreateAuthorityHintCommand.ENDPOINT,
        DeleteAuthorityHintCommand.ENDPOINT,
        // Trust anchor hint endpoints (from service commands)
        FindTrustAnchorHintsByAccountCommand.ENDPOINT,
        CreateTrustAnchorHintCommand.ENDPOINT,
        DeleteTrustAnchorHintCommand.ENDPOINT,
        // Critical claim endpoints (from endpoint commands)
        ListCriticalClaimsEndpointCommand.ENDPOINT,
        CreateCriticalClaimEndpointCommand.ENDPOINT,
        DeleteCriticalClaimEndpointCommand.ENDPOINT,
        // Metadata policy endpoints (from endpoint commands)
        ListMetadataPoliciesEndpointCommand.ENDPOINT,
        CreateMetadataPolicyEndpointCommand.ENDPOINT,
        DeleteMetadataPolicyEndpointCommand.ENDPOINT,
        // Received trust mark endpoints (from service commands)
        ListReceivedTrustMarksCommand.ENDPOINT,
        CreateReceivedTrustMarkCommand.ENDPOINT,
        DeleteReceivedTrustMarkCommand.ENDPOINT,
        // Subordinate constraint endpoints (from service commands)
        GetSubordinateConstraintsCommand.ENDPOINT,
        SetSubordinateConstraintsCommand.ENDPOINT,
        DeleteSubordinateConstraintsCommand.ENDPOINT,
        // Log endpoints (from endpoint commands)
        ListLogsEndpointCommand.ENDPOINT,
        // Cache endpoints (from endpoint commands)
        GetCacheStatsEndpointCommand.ENDPOINT,
        ClearCacheEndpointCommand.ENDPOINT
    )
)

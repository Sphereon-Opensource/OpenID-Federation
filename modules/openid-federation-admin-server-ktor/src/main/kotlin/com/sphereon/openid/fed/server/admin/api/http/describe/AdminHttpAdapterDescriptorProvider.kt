package com.sphereon.openid.fed.server.admin.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.StaticPublicApiDescriptor
import com.sphereon.openid.fed.server.admin.api.http.AdminHttpAdapter
import com.sphereon.openid.fed.server.admin.api.http.command.ClearCacheEndpointCommand
import com.sphereon.openid.fed.server.admin.api.http.command.GetCacheStatsEndpointCommand
import com.sphereon.openid.fed.server.admin.api.http.command.ListLogsEndpointCommand
import com.sphereon.openid.fed.services.command.account.CreateAccountCommand
import com.sphereon.openid.fed.services.command.account.DeleteAccountCommand
import com.sphereon.openid.fed.services.command.account.GetAllAccountsCommand
import com.sphereon.openid.fed.services.command.authorityHint.CreateAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.DeleteAuthorityHintCommand
import com.sphereon.openid.fed.services.command.authorityHint.FindAuthorityHintsByAccountCommand
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.PublishEntityConfigurationCommand
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommand
import com.sphereon.openid.fed.services.command.jwk.GetKeysCommand
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyCommand
import com.sphereon.openid.fed.services.command.metadata.CreateMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.DeleteMetadataCommand
import com.sphereon.openid.fed.services.command.metadata.FindMetadataByAccountCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.CreateMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.DeleteMetadataPolicyCommand
import com.sphereon.openid.fed.services.command.metadataPolicy.FindMetadataPolicyByAccountCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksCommand
import com.sphereon.openid.fed.services.command.subordinate.CreateSubordinateCommand
import com.sphereon.openid.fed.services.command.subordinate.CreateSubordinateJwkCommand
import com.sphereon.openid.fed.services.command.subordinate.CreateSubordinateMetadataCommand
import com.sphereon.openid.fed.services.command.subordinate.DeleteSubordinateCommand
import com.sphereon.openid.fed.services.command.subordinate.DeleteSubordinateJwkCommand
import com.sphereon.openid.fed.services.command.subordinate.DeleteSubordinateMetadataCommand
import com.sphereon.openid.fed.services.command.subordinate.FindSubordinateMetadataCommand
import com.sphereon.openid.fed.services.command.subordinate.FindSubordinatesByAccountCommand
import com.sphereon.openid.fed.services.command.subordinate.GetSubordinateJwksCommand
import com.sphereon.openid.fed.services.command.subordinate.GetSubordinateStatementCommand
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementCommand
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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * AppScope descriptor provider for AdminHttpAdapter.
 *
 * Uses StaticPublicApiDescriptor to provide endpoint metadata from service command
 * ENDPOINT descriptors (single source of truth). Non-1:1 endpoints (cache, logs)
 * reference their own endpoint command descriptors.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = HttpAdapterDescriptorProvider::class, multibinding = true)
class AdminHttpAdapterDescriptorProvider : StaticPublicApiDescriptor(
    adapterId = AdminHttpAdapter.ID,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    ),
    endpoints = listOf(
        // Account endpoints (from service commands)
        GetAllAccountsCommand.ENDPOINT,
        CreateAccountCommand.ENDPOINT,
        DeleteAccountCommand.ENDPOINT,
        // Key endpoints (from service commands)
        GetKeysCommand.ENDPOINT,
        CreateKeyCommand.ENDPOINT,
        RevokeKeyCommand.ENDPOINT,
        // Subordinate endpoints (from service commands)
        FindSubordinatesByAccountCommand.ENDPOINT,
        CreateSubordinateCommand.ENDPOINT,
        DeleteSubordinateCommand.ENDPOINT,
        GetSubordinateJwksCommand.ENDPOINT,
        CreateSubordinateJwkCommand.ENDPOINT,
        DeleteSubordinateJwkCommand.ENDPOINT,
        GetSubordinateStatementCommand.ENDPOINT,
        PublishSubordinateStatementCommand.ENDPOINT,
        FindSubordinateMetadataCommand.ENDPOINT,
        CreateSubordinateMetadataCommand.ENDPOINT,
        DeleteSubordinateMetadataCommand.ENDPOINT,
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
        // Critical claim endpoints (from service commands)
        FindCriticalClaimsByAccountCommand.ENDPOINT,
        CreateCriticalClaimCommand.ENDPOINT,
        DeleteCriticalClaimCommand.ENDPOINT,
        // Metadata policy endpoints (from service commands)
        FindMetadataPolicyByAccountCommand.ENDPOINT,
        CreateMetadataPolicyCommand.ENDPOINT,
        DeleteMetadataPolicyCommand.ENDPOINT,
        // Received trust mark endpoints (from service commands)
        ListReceivedTrustMarksCommand.ENDPOINT,
        CreateReceivedTrustMarkCommand.ENDPOINT,
        DeleteReceivedTrustMarkCommand.ENDPOINT,
        // Log endpoints (non-1:1, from endpoint commands)
        ListLogsEndpointCommand.ENDPOINT,
        // Cache endpoints (non-1:1, from endpoint commands)
        GetCacheStatsEndpointCommand.ENDPOINT,
        ClearCacheEndpointCommand.ENDPOINT
    )
)

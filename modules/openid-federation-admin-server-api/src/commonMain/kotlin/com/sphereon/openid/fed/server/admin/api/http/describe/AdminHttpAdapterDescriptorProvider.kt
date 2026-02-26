package com.sphereon.openid.fed.server.admin.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescription
import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.openid.fed.server.admin.api.http.AdminHttpAdapter
import com.sphereon.openid.fed.server.admin.api.http.command.*
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * AppScope descriptor provider for AdminHttpAdapter.
 *
 * This provides metadata-only information about the adapter's endpoints,
 * allowing the HttpAdapterCatalog to be built at startup without instantiating
 * SessionScope adapters.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = HttpAdapterDescriptorProvider::class, multibinding = true)
class AdminHttpAdapterDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = AdminHttpAdapter.ID

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = ""
        ),
        endpoints = listOf(
            // Account endpoints
            ListAccountsEndpointCommand.ENDPOINT,
            CreateAccountEndpointCommand.ENDPOINT,
            DeleteAccountEndpointCommand.ENDPOINT,
            // Key endpoints
            ListKeysEndpointCommand.ENDPOINT,
            CreateKeyEndpointCommand.ENDPOINT,
            RevokeKeyEndpointCommand.ENDPOINT,
            // Subordinate endpoints
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
            // Trust mark endpoints
            ListTrustMarksEndpointCommand.ENDPOINT,
            CreateTrustMarkEndpointCommand.ENDPOINT,
            DeleteTrustMarkEndpointCommand.ENDPOINT,
            // Trust mark type endpoints
            ListTrustMarkTypesEndpointCommand.ENDPOINT,
            CreateTrustMarkTypeEndpointCommand.ENDPOINT,
            GetTrustMarkTypeEndpointCommand.ENDPOINT,
            DeleteTrustMarkTypeEndpointCommand.ENDPOINT,
            GetTrustMarkTypeIssuersEndpointCommand.ENDPOINT,
            AddTrustMarkTypeIssuerEndpointCommand.ENDPOINT,
            RemoveTrustMarkTypeIssuerEndpointCommand.ENDPOINT,
            // Entity statement endpoints
            GetEntityStatementEndpointCommand.ENDPOINT,
            PublishEntityStatementEndpointCommand.ENDPOINT,
            // Metadata endpoints
            ListMetadataEndpointCommand.ENDPOINT,
            CreateMetadataEndpointCommand.ENDPOINT,
            DeleteMetadataEndpointCommand.ENDPOINT,
            // Authority hint endpoints
            ListAuthorityHintsEndpointCommand.ENDPOINT,
            CreateAuthorityHintEndpointCommand.ENDPOINT,
            DeleteAuthorityHintEndpointCommand.ENDPOINT,
            // Critical claim endpoints
            ListCriticalClaimsEndpointCommand.ENDPOINT,
            CreateCriticalClaimEndpointCommand.ENDPOINT,
            DeleteCriticalClaimEndpointCommand.ENDPOINT,
            // Metadata policy endpoints
            ListMetadataPoliciesEndpointCommand.ENDPOINT,
            CreateMetadataPolicyEndpointCommand.ENDPOINT,
            DeleteMetadataPolicyEndpointCommand.ENDPOINT,
            // Received trust mark endpoints
            ListReceivedTrustMarksEndpointCommand.ENDPOINT,
            CreateReceivedTrustMarkEndpointCommand.ENDPOINT,
            DeleteReceivedTrustMarkEndpointCommand.ENDPOINT,
            // Log endpoints
            ListLogsEndpointCommand.ENDPOINT,
            // Cache endpoints
            GetCacheStatsEndpointCommand.ENDPOINT,
            ClearCacheEndpointCommand.ENDPOINT
        )
    )
}

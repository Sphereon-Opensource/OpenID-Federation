package com.sphereon.openid.fed.server.federation.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescription
import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.openid.fed.server.federation.api.http.FederationHttpAdapter
import com.sphereon.openid.fed.server.federation.api.http.command.*
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * AppScope descriptor provider for FederationHttpAdapter.
 *
 * This provides metadata-only information about the adapter's endpoints,
 * allowing the HttpAdapterCatalog to be built at startup without instantiating
 * SessionScope adapters.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = HttpAdapterDescriptorProvider::class, multibinding = true)
class FederationHttpAdapterDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = FederationHttpAdapter.ID

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = ""
        ),
        endpoints = listOf(
            // Entity Configuration endpoints
            GetEntityConfigurationEndpointCommand.ENDPOINT,
            GetAccountEntityConfigurationEndpointCommand.ENDPOINT,
            // List subordinates endpoints
            ListSubordinatesRootEndpointCommand.ENDPOINT,
            ListSubordinatesAccountEndpointCommand.ENDPOINT,
            // Fetch subordinate statement endpoints
            FetchSubordinateRootEndpointCommand.ENDPOINT,
            FetchSubordinateAccountEndpointCommand.ENDPOINT,
            // Trust mark status endpoints
            TrustMarkStatusRootEndpointCommand.ENDPOINT,
            TrustMarkStatusAccountEndpointCommand.ENDPOINT,
            // Trust mark list endpoints
            TrustMarkListRootEndpointCommand.ENDPOINT,
            TrustMarkListAccountEndpointCommand.ENDPOINT,
            // Get trust mark endpoints
            GetTrustMarkRootEndpointCommand.ENDPOINT,
            GetTrustMarkAccountEndpointCommand.ENDPOINT,
            // Historical keys endpoints
            HistoricalKeysRootEndpointCommand.ENDPOINT,
            HistoricalKeysAccountEndpointCommand.ENDPOINT,
            // Resolve endpoints
            ResolveRootEndpointCommand.ENDPOINT,
            ResolveAccountEndpointCommand.ENDPOINT
        )
    )
}

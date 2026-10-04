package com.sphereon.openid.fed.server.federation.api.http.describe

import com.sphereon.core.api.http.describe.HttpAdapterDescription
import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.openid.fed.server.federation.api.http.FederationHttpAdapter
import com.sphereon.openid.fed.server.federation.api.http.command.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * AppScope descriptor provider for FederationHttpAdapter.
 *
 * This provides metadata-only information about the adapter's endpoints,
 * allowing the HttpAdapterCatalog to be built at startup without instantiating
 * SessionScope adapters.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<HttpAdapterDescriptorProvider>())
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
            GetEntityConfigurationEndpointCommand.ENDPOINT.copy(handlerCommandId = GetEntityConfigurationEndpointCommand.COMMAND_ID),
            GetAccountEntityConfigurationEndpointCommand.ENDPOINT.copy(handlerCommandId = GetAccountEntityConfigurationEndpointCommand.COMMAND_ID),
            // List subordinates endpoints (GET + POST)
            ListSubordinatesRootEndpointCommand.ENDPOINT.copy(handlerCommandId = ListSubordinatesRootEndpointCommand.COMMAND_ID),
            PostListSubordinatesRootEndpointCommand.ENDPOINT.copy(handlerCommandId = PostListSubordinatesRootEndpointCommand.COMMAND_ID),
            ListSubordinatesAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = ListSubordinatesAccountEndpointCommand.COMMAND_ID),
            PostListSubordinatesAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = PostListSubordinatesAccountEndpointCommand.COMMAND_ID),
            // Fetch subordinate statement endpoints (GET + POST)
            FetchSubordinateRootEndpointCommand.ENDPOINT.copy(handlerCommandId = FetchSubordinateRootEndpointCommand.COMMAND_ID),
            PostFetchSubordinateRootEndpointCommand.ENDPOINT.copy(handlerCommandId = PostFetchSubordinateRootEndpointCommand.COMMAND_ID),
            FetchSubordinateAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = FetchSubordinateAccountEndpointCommand.COMMAND_ID),
            PostFetchSubordinateAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = PostFetchSubordinateAccountEndpointCommand.COMMAND_ID),
            // Trust mark status endpoints (GET + POST)
            GetTrustMarkStatusRootEndpointCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkStatusRootEndpointCommand.COMMAND_ID),
            TrustMarkStatusRootEndpointCommand.ENDPOINT.copy(handlerCommandId = TrustMarkStatusRootEndpointCommand.COMMAND_ID),
            GetTrustMarkStatusAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkStatusAccountEndpointCommand.COMMAND_ID),
            TrustMarkStatusAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = TrustMarkStatusAccountEndpointCommand.COMMAND_ID),
            // Trust mark list endpoints (GET + POST)
            TrustMarkListRootEndpointCommand.ENDPOINT.copy(handlerCommandId = TrustMarkListRootEndpointCommand.COMMAND_ID),
            PostTrustMarkListRootEndpointCommand.ENDPOINT.copy(handlerCommandId = PostTrustMarkListRootEndpointCommand.COMMAND_ID),
            TrustMarkListAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = TrustMarkListAccountEndpointCommand.COMMAND_ID),
            PostTrustMarkListAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = PostTrustMarkListAccountEndpointCommand.COMMAND_ID),
            // Get trust mark endpoints (GET + POST)
            GetTrustMarkRootEndpointCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkRootEndpointCommand.COMMAND_ID),
            PostGetTrustMarkRootEndpointCommand.ENDPOINT.copy(handlerCommandId = PostGetTrustMarkRootEndpointCommand.COMMAND_ID),
            GetTrustMarkAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = GetTrustMarkAccountEndpointCommand.COMMAND_ID),
            PostGetTrustMarkAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = PostGetTrustMarkAccountEndpointCommand.COMMAND_ID),
            // Historical keys endpoints
            HistoricalKeysRootEndpointCommand.ENDPOINT.copy(handlerCommandId = HistoricalKeysRootEndpointCommand.COMMAND_ID),
            HistoricalKeysAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = HistoricalKeysAccountEndpointCommand.COMMAND_ID),
            // Resolve endpoints (GET + POST)
            ResolveRootEndpointCommand.ENDPOINT.copy(handlerCommandId = ResolveRootEndpointCommand.COMMAND_ID),
            PostResolveRootEndpointCommand.ENDPOINT.copy(handlerCommandId = PostResolveRootEndpointCommand.COMMAND_ID),
            ResolveAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = ResolveAccountEndpointCommand.COMMAND_ID),
            PostResolveAccountEndpointCommand.ENDPOINT.copy(handlerCommandId = PostResolveAccountEndpointCommand.COMMAND_ID)
        )
    )
}

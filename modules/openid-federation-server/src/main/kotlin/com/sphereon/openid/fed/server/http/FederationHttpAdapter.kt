package com.sphereon.openid.fed.server.http

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.server.http.command.*
import com.sphereon.di.context.Named
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * HTTP Adapter for the OpenID Federation Server API.
 *
 * This adapter aggregates all federation protocol endpoint commands and provides
 * the HTTP routing layer using the IDK's CommandBackedHttpAdapter pattern.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * Endpoints are available both at root level (for default account) and
 * under /{username} for account-specific access.
 */
@Inject
@Named(FederationHttpAdapter.ID)
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = HttpAdapter::class, multibinding = true)
class FederationHttpAdapter(
    execution: SessionExecution,
    // Entity Configuration endpoints
    private val getEntityConfigurationEndpoint: GetEntityConfigurationEndpointCommand,
    private val getAccountEntityConfigurationEndpoint: GetAccountEntityConfigurationEndpointCommand,
    // List subordinates endpoints
    private val listSubordinatesRootEndpoint: ListSubordinatesRootEndpointCommand,
    private val listSubordinatesAccountEndpoint: ListSubordinatesAccountEndpointCommand,
    // Fetch subordinate statement endpoints
    private val fetchSubordinateRootEndpoint: FetchSubordinateRootEndpointCommand,
    private val fetchSubordinateAccountEndpoint: FetchSubordinateAccountEndpointCommand,
    // Trust mark status endpoints
    private val trustMarkStatusRootEndpoint: TrustMarkStatusRootEndpointCommand,
    private val trustMarkStatusAccountEndpoint: TrustMarkStatusAccountEndpointCommand,
    // Trust mark list endpoints
    private val trustMarkListRootEndpoint: TrustMarkListRootEndpointCommand,
    private val trustMarkListAccountEndpoint: TrustMarkListAccountEndpointCommand,
    // Get trust mark endpoints
    private val getTrustMarkRootEndpoint: GetTrustMarkRootEndpointCommand,
    private val getTrustMarkAccountEndpoint: GetTrustMarkAccountEndpointCommand,
    // Historical keys endpoints
    private val historicalKeysRootEndpoint: HistoricalKeysRootEndpointCommand,
    private val historicalKeysAccountEndpoint: HistoricalKeysAccountEndpointCommand,
    // Resolve endpoints
    private val resolveRootEndpoint: ResolveRootEndpointCommand,
    private val resolveAccountEndpoint: ResolveAccountEndpointCommand
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    )
) {
    companion object {
        const val ID = "FEDERATION_SERVER"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        // Entity Configuration endpoints
        getEntityConfigurationEndpoint,
        getAccountEntityConfigurationEndpoint,
        // List subordinates endpoints
        listSubordinatesRootEndpoint,
        listSubordinatesAccountEndpoint,
        // Fetch subordinate statement endpoints
        fetchSubordinateRootEndpoint,
        fetchSubordinateAccountEndpoint,
        // Trust mark status endpoints
        trustMarkStatusRootEndpoint,
        trustMarkStatusAccountEndpoint,
        // Trust mark list endpoints
        trustMarkListRootEndpoint,
        trustMarkListAccountEndpoint,
        // Get trust mark endpoints
        getTrustMarkRootEndpoint,
        getTrustMarkAccountEndpoint,
        // Historical keys endpoints
        historicalKeysRootEndpoint,
        historicalKeysAccountEndpoint,
        // Resolve endpoints
        resolveRootEndpoint,
        resolveAccountEndpoint
    )

    override val openApiHints: OpenApiHints = OpenApiHints(
        tags = setOf(
            "federation",
            "trust-marks",
            "keys",
            "resolution"
        ),
        operationIdPrefix = "federation"
    )

    /**
     * DI Component interface for accessing the FederationHttpAdapter from session context.
     */
    @ContributesTo(SessionScope::class)
    interface Component {
        val federationHttpAdapter: FederationHttpAdapter
    }
}

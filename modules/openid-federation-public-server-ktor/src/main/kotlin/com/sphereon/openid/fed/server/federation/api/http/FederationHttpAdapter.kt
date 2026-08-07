package com.sphereon.openid.fed.server.federation.api.http

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.server.federation.api.http.command.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.SingleIn

/**
 * HTTP Adapter for the OpenID Federation Server API.
 *
 * This adapter aggregates all federation protocol endpoint commands and provides
 * the HTTP routing layer using the IDK's CommandBackedHttpAdapter pattern.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * Endpoints are available both at root level (for default account) and
 * under /{username} for account-specific access.
 *
 * Note: do not put `@Named` on this class. `DefaultHttpAdapterDispatcher` injects
 * an unqualified `Set<HttpAdapter>`; a class-level `@Named` would put this adapter
 * into a named set and leave dispatch with only `NoOpHttpAdapter`.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class FederationHttpAdapter(
    execution: SessionExecution,
    // Entity Configuration endpoints
    private val getEntityConfigurationEndpoint: GetEntityConfigurationEndpointCommand,
    private val getAccountEntityConfigurationEndpoint: GetAccountEntityConfigurationEndpointCommand,
    // List subordinates endpoints (GET + POST)
    private val listSubordinatesRootEndpoint: ListSubordinatesRootEndpointCommand,
    private val postListSubordinatesRootEndpoint: PostListSubordinatesRootEndpointCommand,
    private val listSubordinatesAccountEndpoint: ListSubordinatesAccountEndpointCommand,
    private val postListSubordinatesAccountEndpoint: PostListSubordinatesAccountEndpointCommand,
    // Fetch subordinate statement endpoints (GET + POST)
    private val fetchSubordinateRootEndpoint: FetchSubordinateRootEndpointCommand,
    private val postFetchSubordinateRootEndpoint: PostFetchSubordinateRootEndpointCommand,
    private val fetchSubordinateAccountEndpoint: FetchSubordinateAccountEndpointCommand,
    private val postFetchSubordinateAccountEndpoint: PostFetchSubordinateAccountEndpointCommand,
    // Trust mark status endpoints (GET + POST)
    private val getTrustMarkStatusRootEndpoint: GetTrustMarkStatusRootEndpointCommand,
    private val trustMarkStatusRootEndpoint: TrustMarkStatusRootEndpointCommand,
    private val getTrustMarkStatusAccountEndpoint: GetTrustMarkStatusAccountEndpointCommand,
    private val trustMarkStatusAccountEndpoint: TrustMarkStatusAccountEndpointCommand,
    // Trust mark list endpoints (GET + POST)
    private val trustMarkListRootEndpoint: TrustMarkListRootEndpointCommand,
    private val postTrustMarkListRootEndpoint: PostTrustMarkListRootEndpointCommand,
    private val trustMarkListAccountEndpoint: TrustMarkListAccountEndpointCommand,
    private val postTrustMarkListAccountEndpoint: PostTrustMarkListAccountEndpointCommand,
    // Get trust mark endpoints (GET + POST)
    private val getTrustMarkRootEndpoint: GetTrustMarkRootEndpointCommand,
    private val postGetTrustMarkRootEndpoint: PostGetTrustMarkRootEndpointCommand,
    private val getTrustMarkAccountEndpoint: GetTrustMarkAccountEndpointCommand,
    private val postGetTrustMarkAccountEndpoint: PostGetTrustMarkAccountEndpointCommand,
    // Historical keys endpoints
    private val historicalKeysRootEndpoint: HistoricalKeysRootEndpointCommand,
    private val historicalKeysAccountEndpoint: HistoricalKeysAccountEndpointCommand,
    // Resolve endpoints (GET + POST)
    private val resolveRootEndpoint: ResolveRootEndpointCommand,
    private val postResolveRootEndpoint: PostResolveRootEndpointCommand,
    private val resolveAccountEndpoint: ResolveAccountEndpointCommand,
    private val postResolveAccountEndpoint: PostResolveAccountEndpointCommand
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    )
) {
    companion object {
        /** CommandId-compatible adapter id (module.service.command). */
        const val ID = "fed.server.http"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        // Entity Configuration endpoints
        getEntityConfigurationEndpoint,
        getAccountEntityConfigurationEndpoint,
        // List subordinates endpoints (GET + POST)
        listSubordinatesRootEndpoint,
        postListSubordinatesRootEndpoint,
        listSubordinatesAccountEndpoint,
        postListSubordinatesAccountEndpoint,
        // Fetch subordinate statement endpoints (GET + POST)
        fetchSubordinateRootEndpoint,
        postFetchSubordinateRootEndpoint,
        fetchSubordinateAccountEndpoint,
        postFetchSubordinateAccountEndpoint,
        // Trust mark status endpoints (GET + POST)
        getTrustMarkStatusRootEndpoint,
        trustMarkStatusRootEndpoint,
        getTrustMarkStatusAccountEndpoint,
        trustMarkStatusAccountEndpoint,
        // Trust mark list endpoints (GET + POST)
        trustMarkListRootEndpoint,
        postTrustMarkListRootEndpoint,
        trustMarkListAccountEndpoint,
        postTrustMarkListAccountEndpoint,
        // Get trust mark endpoints (GET + POST)
        getTrustMarkRootEndpoint,
        postGetTrustMarkRootEndpoint,
        getTrustMarkAccountEndpoint,
        postGetTrustMarkAccountEndpoint,
        // Historical keys endpoints
        historicalKeysRootEndpoint,
        historicalKeysAccountEndpoint,
        // Resolve endpoints (GET + POST)
        resolveRootEndpoint,
        postResolveRootEndpoint,
        resolveAccountEndpoint,
        postResolveAccountEndpoint
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
    interface Graph {
        val federationHttpAdapter: FederationHttpAdapter
    }
}

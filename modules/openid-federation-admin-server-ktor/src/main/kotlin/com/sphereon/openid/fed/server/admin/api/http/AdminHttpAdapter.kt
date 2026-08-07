package com.sphereon.openid.fed.server.admin.api.http

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.PublicApiHttpAdapter
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.server.admin.api.http.command.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * HTTP Adapter for the OpenID Federation Admin API.
 *
 * This adapter aggregates all admin endpoint commands and provides
 * the HTTP routing layer using the IDK's PublicApiHttpAdapter pattern.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * The server configuration can add a server prefix like "/api" if needed.
 *
 * Note: do not put `@Named` on this class. `DefaultHttpAdapterDispatcher` injects
 * an unqualified `Set<HttpAdapter>`; a class-level `@Named` would put this adapter
 * into a named set and leave dispatch with only `NoOpHttpAdapter`.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class AdminHttpAdapter(
    execution: SessionExecution,
    private val configBinder: OidfConfigBinder,
    /**
     * Optional LEGACY `/accounts` endpoints from `account-http`.
     * Empty when that jar is not on the classpath (deployment = dependency presence only).
     */
    private val accountEndpointContributions: Set<AdminAccountEndpointContribution>,
    // Key endpoints
    private val listKeysEndpoint: ListKeysEndpointCommand,
    private val createKeyEndpoint: CreateKeyEndpointCommand,
    private val revokeKeyEndpoint: RevokeKeyEndpointCommand,
    // Subordinate endpoints
    private val listSubordinatesEndpoint: ListSubordinatesEndpointCommand,
    private val createSubordinateEndpoint: CreateSubordinateEndpointCommand,
    private val deleteSubordinateEndpoint: DeleteSubordinateEndpointCommand,
    private val listSubordinateKeysEndpoint: ListSubordinateKeysEndpointCommand,
    private val createSubordinateKeyEndpoint: CreateSubordinateKeyEndpointCommand,
    private val deleteSubordinateKeyEndpoint: DeleteSubordinateKeyEndpointCommand,
    private val getSubordinateStatementEndpoint: GetSubordinateStatementEndpointCommand,
    private val publishSubordinateStatementEndpoint: PublishSubordinateStatementEndpointCommand,
    private val listSubordinateMetadataEndpoint: ListSubordinateMetadataEndpointCommand,
    private val createSubordinateMetadataEndpoint: CreateSubordinateMetadataEndpointCommand,
    private val deleteSubordinateMetadataEndpoint: DeleteSubordinateMetadataEndpointCommand,
    // Trust mark endpoints
    private val listTrustMarksEndpoint: ListTrustMarksEndpointCommand,
    private val createTrustMarkEndpoint: CreateTrustMarkEndpointCommand,
    private val deleteTrustMarkEndpoint: DeleteTrustMarkEndpointCommand,
    // Trust mark type endpoints
    private val listTrustMarkTypesEndpoint: ListTrustMarkTypesEndpointCommand,
    private val createTrustMarkTypeEndpoint: CreateTrustMarkTypeEndpointCommand,
    private val getTrustMarkTypeEndpoint: GetTrustMarkTypeEndpointCommand,
    private val deleteTrustMarkTypeEndpoint: DeleteTrustMarkTypeEndpointCommand,
    private val getTrustMarkTypeIssuersEndpoint: GetTrustMarkTypeIssuersEndpointCommand,
    private val addTrustMarkTypeIssuerEndpoint: AddTrustMarkTypeIssuerEndpointCommand,
    private val removeTrustMarkTypeIssuerEndpoint: RemoveTrustMarkTypeIssuerEndpointCommand,
    // Entity statement endpoints
    private val getEntityStatementEndpoint: GetEntityStatementEndpointCommand,
    private val publishEntityStatementEndpoint: PublishEntityStatementEndpointCommand,
    // Metadata endpoints
    private val listMetadataEndpoint: ListMetadataEndpointCommand,
    private val createMetadataEndpoint: CreateMetadataEndpointCommand,
    private val deleteMetadataEndpoint: DeleteMetadataEndpointCommand,
    // Authority hint endpoints
    private val listAuthorityHintsEndpoint: ListAuthorityHintsEndpointCommand,
    private val createAuthorityHintEndpoint: CreateAuthorityHintEndpointCommand,
    private val deleteAuthorityHintEndpoint: DeleteAuthorityHintEndpointCommand,
    // Trust anchor hint endpoints
    private val listTrustAnchorHintsEndpoint: ListTrustAnchorHintsEndpointCommand,
    private val createTrustAnchorHintEndpoint: CreateTrustAnchorHintEndpointCommand,
    private val deleteTrustAnchorHintEndpoint: DeleteTrustAnchorHintEndpointCommand,
    // Critical claim endpoints
    private val listCriticalClaimsEndpoint: ListCriticalClaimsEndpointCommand,
    private val createCriticalClaimEndpoint: CreateCriticalClaimEndpointCommand,
    private val deleteCriticalClaimEndpoint: DeleteCriticalClaimEndpointCommand,
    // Metadata policy endpoints
    private val listMetadataPoliciesEndpoint: ListMetadataPoliciesEndpointCommand,
    private val createMetadataPolicyEndpoint: CreateMetadataPolicyEndpointCommand,
    private val deleteMetadataPolicyEndpoint: DeleteMetadataPolicyEndpointCommand,
    // Received trust mark endpoints
    private val listReceivedTrustMarksEndpoint: ListReceivedTrustMarksEndpointCommand,
    private val createReceivedTrustMarkEndpoint: CreateReceivedTrustMarkEndpointCommand,
    private val deleteReceivedTrustMarkEndpoint: DeleteReceivedTrustMarkEndpointCommand,
    // Subordinate constraint endpoints
    private val getSubordinateConstraintsEndpoint: GetSubordinateConstraintsEndpointCommand,
    private val setSubordinateConstraintsEndpoint: SetSubordinateConstraintsEndpointCommand,
    private val deleteSubordinateConstraintsEndpoint: DeleteSubordinateConstraintsEndpointCommand,
    // Log endpoints
    private val listLogsEndpoint: ListLogsEndpointCommand,
    // Cache endpoints
    private val getCacheStatsEndpoint: GetCacheStatsEndpointCommand,
    private val clearCacheEndpoint: ClearCacheEndpointCommand
) : PublicApiHttpAdapter(
    id = ID,
    sessionExecution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    )
) {
    companion object {
        /** CommandId-compatible adapter id (module.service.command). */
        const val ID = "fed.admin.http"
    }

    /**
     * Service commands for binary transport and metadata.
     * Currently empty as endpoint commands handle HTTP dispatch.
     * Will be populated when binary transport support is enabled.
     */
    override val serviceCommands: List<ServiceCommand<*, *, FederationError>> = emptyList()

    override val endpointCommands: List<HttpEndpointCommand> = buildList {
        // Account management REST: only when account-http contributed commands AND LEGACY mode
        if (configBinder.getIdentityConfig().isAccount) {
            accountEndpointContributions.forEach { addAll(it.endpointCommands) }
        }
        add(listKeysEndpoint)
        add(createKeyEndpoint)
        add(revokeKeyEndpoint)
        add(listSubordinatesEndpoint)
        add(createSubordinateEndpoint)
        add(deleteSubordinateEndpoint)
        add(listSubordinateKeysEndpoint)
        add(createSubordinateKeyEndpoint)
        add(deleteSubordinateKeyEndpoint)
        add(getSubordinateStatementEndpoint)
        add(publishSubordinateStatementEndpoint)
        add(listSubordinateMetadataEndpoint)
        add(createSubordinateMetadataEndpoint)
        add(deleteSubordinateMetadataEndpoint)
        add(listTrustMarksEndpoint)
        add(createTrustMarkEndpoint)
        add(deleteTrustMarkEndpoint)
        add(listTrustMarkTypesEndpoint)
        add(createTrustMarkTypeEndpoint)
        add(getTrustMarkTypeEndpoint)
        add(deleteTrustMarkTypeEndpoint)
        add(getTrustMarkTypeIssuersEndpoint)
        add(addTrustMarkTypeIssuerEndpoint)
        add(removeTrustMarkTypeIssuerEndpoint)
        add(getEntityStatementEndpoint)
        add(publishEntityStatementEndpoint)
        add(listMetadataEndpoint)
        add(createMetadataEndpoint)
        add(deleteMetadataEndpoint)
        add(listAuthorityHintsEndpoint)
        add(createAuthorityHintEndpoint)
        add(deleteAuthorityHintEndpoint)
        add(listTrustAnchorHintsEndpoint)
        add(createTrustAnchorHintEndpoint)
        add(deleteTrustAnchorHintEndpoint)
        add(listCriticalClaimsEndpoint)
        add(createCriticalClaimEndpoint)
        add(deleteCriticalClaimEndpoint)
        add(listMetadataPoliciesEndpoint)
        add(createMetadataPolicyEndpoint)
        add(deleteMetadataPolicyEndpoint)
        add(listReceivedTrustMarksEndpoint)
        add(createReceivedTrustMarkEndpoint)
        add(deleteReceivedTrustMarkEndpoint)
        add(getSubordinateConstraintsEndpoint)
        add(setSubordinateConstraintsEndpoint)
        add(deleteSubordinateConstraintsEndpoint)
        add(listLogsEndpoint)
        add(getCacheStatsEndpoint)
        add(clearCacheEndpoint)
    }
}

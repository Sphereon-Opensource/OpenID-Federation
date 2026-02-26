package com.sphereon.openid.fed.server.admin.api.http

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.server.admin.api.http.command.*
import com.sphereon.di.context.Named
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * HTTP Adapter for the OpenID Federation Admin API.
 *
 * This adapter aggregates all admin endpoint commands and provides
 * the HTTP routing layer using the IDK's CommandBackedHttpAdapter pattern.
 *
 * All endpoints are mounted at the root path (no base path prefix).
 * The server configuration can add a server prefix like "/api" if needed.
 */
@Inject
@Named(AdminHttpAdapter.ID)
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = HttpAdapter::class, multibinding = true)
class AdminHttpAdapter(
    execution: SessionExecution,
    // Account endpoints
    private val listAccountsEndpoint: ListAccountsEndpointCommand,
    private val createAccountEndpoint: CreateAccountEndpointCommand,
    private val deleteAccountEndpoint: DeleteAccountEndpointCommand,
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
    // Log endpoints
    private val listLogsEndpoint: ListLogsEndpointCommand,
    // Cache endpoints
    private val getCacheStatsEndpoint: GetCacheStatsEndpointCommand,
    private val clearCacheEndpoint: ClearCacheEndpointCommand
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = ""
    )
) {
    companion object {
        const val ID = "FEDERATION_ADMIN"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        // Account endpoints
        listAccountsEndpoint,
        createAccountEndpoint,
        deleteAccountEndpoint,
        // Key endpoints
        listKeysEndpoint,
        createKeyEndpoint,
        revokeKeyEndpoint,
        // Subordinate endpoints
        listSubordinatesEndpoint,
        createSubordinateEndpoint,
        deleteSubordinateEndpoint,
        listSubordinateKeysEndpoint,
        createSubordinateKeyEndpoint,
        deleteSubordinateKeyEndpoint,
        getSubordinateStatementEndpoint,
        publishSubordinateStatementEndpoint,
        listSubordinateMetadataEndpoint,
        createSubordinateMetadataEndpoint,
        deleteSubordinateMetadataEndpoint,
        // Trust mark endpoints
        listTrustMarksEndpoint,
        createTrustMarkEndpoint,
        deleteTrustMarkEndpoint,
        // Trust mark type endpoints
        listTrustMarkTypesEndpoint,
        createTrustMarkTypeEndpoint,
        getTrustMarkTypeEndpoint,
        deleteTrustMarkTypeEndpoint,
        getTrustMarkTypeIssuersEndpoint,
        addTrustMarkTypeIssuerEndpoint,
        removeTrustMarkTypeIssuerEndpoint,
        // Entity statement endpoints
        getEntityStatementEndpoint,
        publishEntityStatementEndpoint,
        // Metadata endpoints
        listMetadataEndpoint,
        createMetadataEndpoint,
        deleteMetadataEndpoint,
        // Authority hint endpoints
        listAuthorityHintsEndpoint,
        createAuthorityHintEndpoint,
        deleteAuthorityHintEndpoint,
        // Critical claim endpoints
        listCriticalClaimsEndpoint,
        createCriticalClaimEndpoint,
        deleteCriticalClaimEndpoint,
        // Metadata policy endpoints
        listMetadataPoliciesEndpoint,
        createMetadataPolicyEndpoint,
        deleteMetadataPolicyEndpoint,
        // Received trust mark endpoints
        listReceivedTrustMarksEndpoint,
        createReceivedTrustMarkEndpoint,
        deleteReceivedTrustMarkEndpoint,
        // Log endpoints
        listLogsEndpoint,
        // Cache endpoints
        getCacheStatsEndpoint,
        clearCacheEndpoint
    )

    override val openApiHints: OpenApiHints = OpenApiHints(
        tags = setOf(
            "accounts",
            "keys",
            "subordinates",
            "trust-marks",
            "entity-statement",
            "metadata",
            "authority-hints",
            "critical-claims",
            "metadata-policy",
            "received-trust-marks",
            "logs",
            "cache"
        ),
        operationIdPrefix = "admin"
    )

    /**
     * DI Component interface for accessing the AdminHttpAdapter from session context.
     */
    @ContributesTo(SessionScope::class)
    interface Component {
        val adminHttpAdapter: AdminHttpAdapter
    }
}

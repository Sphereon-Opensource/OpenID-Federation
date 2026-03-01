package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.errorResponse
import com.sphereon.core.api.http.jsonResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.SubordinateService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Filter parameters for the /list endpoint per OpenID Federation 1.1 spec.
 */
private data class ListFilters(
    val entityType: String? = null,
    val trustMarked: Boolean? = null,
    val trustMarkType: String? = null,
    val intermediate: Boolean? = null
)

// ==================== List Subordinates GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinatesRootEndpointCommand::class)
class ListSubordinatesRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinatesRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinatesRootEndpointCommand.ENDPOINT
), ListSubordinatesRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val filters = ListFilters(
            entityType = request.queryParameters["entity_type"],
            trustMarked = request.queryParameters["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = request.queryParameters["trust_mark_type"],
            intermediate = request.queryParameters["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(subordinateService, json, tenantId, filters)
    }
}

// ==================== List Subordinates POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostListSubordinatesRootEndpointCommand::class)
class PostListSubordinatesRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PostListSubordinatesRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostListSubordinatesRootEndpointCommand.ENDPOINT
), PostListSubordinatesRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val params = request.body?.let { parseListFormParams(it) } ?: emptyMap()
        val filters = ListFilters(
            entityType = params["entity_type"],
            trustMarked = params["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = params["trust_mark_type"],
            intermediate = params["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(subordinateService, json, tenantId, filters)
    }
}

// ==================== List Subordinates GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinatesAccountEndpointCommand::class)
class ListSubordinatesAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinatesAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinatesAccountEndpointCommand.ENDPOINT
), ListSubordinatesAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val filters = ListFilters(
            entityType = request.queryParameters["entity_type"],
            trustMarked = request.queryParameters["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = request.queryParameters["trust_mark_type"],
            intermediate = request.queryParameters["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(subordinateService, json, tenantId, filters)
    }
}

// ==================== List Subordinates POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostListSubordinatesAccountEndpointCommand::class)
class PostListSubordinatesAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PostListSubordinatesAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostListSubordinatesAccountEndpointCommand.ENDPOINT
), PostListSubordinatesAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val params = request.body?.let { parseListFormParams(it) } ?: emptyMap()
        val filters = ListFilters(
            entityType = params["entity_type"],
            trustMarked = params["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = params["trust_mark_type"],
            intermediate = params["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(subordinateService, json, tenantId, filters)
    }
}

// ==================== Shared Helpers ====================

private suspend fun listSubordinatesFiltered(
    subordinateService: SubordinateService,
    json: Json,
    tenantId: String,
    filters: ListFilters
): IdkResult<GenericHttpResponse, IdkError> {
    val hasFilters = filters.entityType != null || filters.trustMarked != null ||
            filters.trustMarkType != null || filters.intermediate != null

    // If no filters, use the fast path
    if (!hasFilters) {
        val result = subordinateService.findSubordinatesByAccountAsArray(tenantId)
        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }

    // Get all subordinates with full details for filtering
    val result = subordinateService.findSubordinatesByAccount(tenantId)
    if (result.isErr) {
        val error = result.error
        return Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
    }

    var subordinates = result.value.toList()
    val metadataQueries = Persistence.subordinateMetadataQueries
    val trustMarkQueries = Persistence.trustMarkQueries

    // Filter by entity_type: subordinate must have metadata with matching key
    if (filters.entityType != null) {
        subordinates = subordinates.filter { sub ->
            metadataQueries.findByAccountIdAndSubordinateIdAndKey(tenantId, sub.id, filters.entityType)
                .executeAsList()
                .isNotEmpty()
        }
    }

    // Filter by trust_mark_type: subordinate must have a trust mark with this type
    if (filters.trustMarkType != null) {
        val trustMarkedSubs = trustMarkQueries
            .findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(tenantId, filters.trustMarkType)
            .executeAsList()
            .toSet()
        subordinates = subordinates.filter { it.identifier in trustMarkedSubs }
    }

    // Filter by trust_marked: if true, include only subordinates with any trust marks;
    // if false, include only subordinates without any trust marks
    if (filters.trustMarked != null) {
        val allTrustMarks = trustMarkQueries.findByAccountId(tenantId).executeAsList()
        val trustMarkedIdentifiers = allTrustMarks.map { it.sub }.toSet()
        subordinates = if (filters.trustMarked) {
            subordinates.filter { it.identifier in trustMarkedIdentifiers }
        } else {
            subordinates.filter { it.identifier !in trustMarkedIdentifiers }
        }
    }

    // Filter by intermediate: if true, include only subordinates that are intermediate entities
    // (i.e., they have their own subordinates). Check if the subordinate's identifier appears
    // as an issuer in published subordinate statements.
    if (filters.intermediate != null) {
        val subordinateStatementQueries = Persistence.subordinateStatementQueries
        subordinates = if (filters.intermediate) {
            subordinates.filter { sub ->
                // An intermediate entity has published subordinate statements as issuer
                subordinateStatementQueries.findByIss(sub.identifier).executeAsList().isNotEmpty()
            }
        } else {
            subordinates.filter { sub ->
                subordinateStatementQueries.findByIss(sub.identifier).executeAsList().isEmpty()
            }
        }
    }

    val identifiers = subordinates.map { it.identifier }.toTypedArray()
    return Ok(jsonResponse(200, json.encodeToString(identifiers)))
}

private fun parseListFormParams(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").associate { param ->
        val parts = param.split("=", limit = 2)
        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
        val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
        key to value
    }
}

package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.server.federation.api.http.auth.FederationEndpointClientAuthService
import com.sphereon.openid.fed.server.federation.api.http.auth.asAuthParams
import com.sphereon.openid.fed.server.federation.api.http.auth.enforceFederationClientAuth
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.command.subordinate.ListPublishedSubordinatesCommand
import com.sphereon.openid.fed.services.command.subordinate.ListPublishedSubordinatesArgs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

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
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinatesRootEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(ListSubordinatesRootEndpointCommand.COMMAND_ID)
class ListSubordinatesRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val listPublished: ListPublishedSubordinatesCommand,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.LIST, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val filters = ListFilters(
            entityType = request.queryParameters["entity_type"],
            trustMarked = request.queryParameters["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = request.queryParameters["trust_mark_type"],
            intermediate = request.queryParameters["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(listPublished, json, tenantId, filters)
    }
}

// ==================== List Subordinates POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostListSubordinatesRootEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(PostListSubordinatesRootEndpointCommand.COMMAND_ID)
class PostListSubordinatesRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val listPublished: ListPublishedSubordinatesCommand,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val params = request.body?.let { parseListFormParams(it) } ?: emptyMap()
        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.LIST, methodIsPost = true,
            params = params,
        )?.let { return Ok(it) }
        val filters = ListFilters(
            entityType = params["entity_type"],
            trustMarked = params["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = params["trust_mark_type"],
            intermediate = params["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(listPublished, json, tenantId, filters)
    }
}

// ==================== List Subordinates GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinatesAccountEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(ListSubordinatesAccountEndpointCommand.COMMAND_ID)
class ListSubordinatesAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val listPublished: ListPublishedSubordinatesCommand,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.LIST, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val filters = ListFilters(
            entityType = request.queryParameters["entity_type"],
            trustMarked = request.queryParameters["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = request.queryParameters["trust_mark_type"],
            intermediate = request.queryParameters["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(listPublished, json, tenantId, filters)
    }
}

// ==================== List Subordinates POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostListSubordinatesAccountEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(PostListSubordinatesAccountEndpointCommand.COMMAND_ID)
class PostListSubordinatesAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val listPublished: ListPublishedSubordinatesCommand,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val params = request.body?.let { parseListFormParams(it) } ?: emptyMap()
        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.LIST, methodIsPost = true,
            params = params,
        )?.let { return Ok(it) }
        val filters = ListFilters(
            entityType = params["entity_type"],
            trustMarked = params["trust_marked"]?.toBooleanStrictOrNull(),
            trustMarkType = params["trust_mark_type"],
            intermediate = params["intermediate"]?.toBooleanStrictOrNull()
        )

        return listSubordinatesFiltered(listPublished, json, tenantId, filters)
    }
}

// ==================== Shared Helpers ====================

private suspend fun listSubordinatesFiltered(
    command: ListPublishedSubordinatesCommand,
    json: Json,
    tenantId: String,
    filters: ListFilters,
): IdkResult<GenericHttpResponse, IdkError> {
    val result = command.execute(ListPublishedSubordinatesArgs(tenantId, filters.entityType, filters.trustMarked, filters.trustMarkType, filters.intermediate))
    return Ok(if (result.isOk) jsonResponse(200, json.encodeToString(result.value)) else FederationErrorResponses.fromServiceError(result.error))
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

package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.errorResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.ResolutionService
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Resolve Trust Chain GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveRootEndpointCommand>())
class ResolveRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = ResolveRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ResolveRootEndpointCommand.ENDPOINT
), ResolveRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val trustAnchor = request.queryParameters["trust_anchor"]

        // Handle multiple entity_type parameters
        val entityTypes = request.queryParameters.entries
            .filter { it.key == "entity_type" }
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchor, entityTypes)
    }
}

// ==================== Resolve Trust Chain POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostResolveRootEndpointCommand>())
class PostResolveRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = PostResolveRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostResolveRootEndpointCommand.ENDPOINT
), PostResolveRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseResolveFormParams(body)

        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val trustAnchor = params["trust_anchor"]
        val entityTypes = params.entries
            .filter { it.key == "entity_type" }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchor, entityTypes)
    }
}

// ==================== Resolve Trust Chain GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveAccountEndpointCommand>())
class ResolveAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = ResolveAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ResolveAccountEndpointCommand.ENDPOINT
), ResolveAccountEndpointCommand {

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

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val trustAnchor = request.queryParameters["trust_anchor"]

        // Handle multiple entity_type parameters
        val entityTypes = request.queryParameters.entries
            .filter { it.key == "entity_type" }
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchor, entityTypes)
    }
}

// ==================== Resolve Trust Chain POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostResolveAccountEndpointCommand>())
class PostResolveAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = PostResolveAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostResolveAccountEndpointCommand.ENDPOINT
), PostResolveAccountEndpointCommand {

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

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseResolveFormParams(body)

        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val trustAnchor = params["trust_anchor"]
        val entityTypes = params.entries
            .filter { it.key == "entity_type" }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchor, entityTypes)
    }
}

// ==================== Shared Helpers ====================

private suspend fun resolveChain(
    resolutionService: ResolutionService,
    tenantId: String,
    sub: String,
    trustAnchor: String?,
    entityTypes: Array<String>?
): IdkResult<GenericHttpResponse, IdkError> {
    // Per 1.1 spec, trust_anchor is optional. When not provided, return an error
    // as the resolution service currently requires it. In a future version, the service
    // could resolve using configured default trust anchors.
    val anchor = trustAnchor
        ?: return Ok(errorResponse(400, "Missing 'trust_anchor' parameter (required for resolution)"))

    val result = resolutionService.getSignedResolveResponseJwt(
        tenantId = tenantId,
        sub = sub,
        trustAnchor = anchor,
        entityTypes = entityTypes
    )

    return if (result.isOk) {
        Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/resolve-response+jwt"),
            body = result.value
        ))
    } else {
        val error = result.error
        Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
    }
}

/**
 * Parse form-urlencoded body parameters. Supports multiple values for the same key
 * by returning the last value (use entries for multi-value iteration).
 */
private fun parseResolveFormParams(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").associate { param ->
        val parts = param.split("=", limit = 2)
        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
        val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
        key to value
    }
}

package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.server.federation.api.http.MultiValueParams
import com.sphereon.openid.fed.server.federation.api.http.auth.FederationEndpointClientAuthService
import com.sphereon.openid.fed.server.federation.api.http.auth.asAuthParams
import com.sphereon.openid.fed.server.federation.api.http.auth.enforceFederationClientAuth
import com.sphereon.openid.fed.core.config.FederationEndpointKind
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
    private val resolutionService: ResolutionService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.RESOLVE, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val trustAnchors = MultiValueParams.fromMap(request.queryParameters, "trust_anchor")
        val entityTypes = MultiValueParams.fromMap(request.queryParameters, "entity_type")
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchors, entityTypes)
    }
}

// ==================== Resolve Trust Chain POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostResolveRootEndpointCommand>())
class PostResolveRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val multi = MultiValueParams.parseFormMulti(body)

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.RESOLVE, methodIsPost = true,
            params = multi.mapValues { it.value.lastOrNull().orEmpty() },
        )?.let { return Ok(it) }

        val sub = MultiValueParams.first(multi, "sub")
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val trustAnchors = MultiValueParams.all(multi, "trust_anchor")
        val entityTypes = MultiValueParams.all(multi, "entity_type")
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchors, entityTypes)
    }
}

// ==================== Resolve Trust Chain GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveAccountEndpointCommand>())
class ResolveAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.RESOLVE, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val trustAnchors = MultiValueParams.fromMap(request.queryParameters, "trust_anchor")
        val entityTypes = MultiValueParams.fromMap(request.queryParameters, "entity_type")
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchors, entityTypes)
    }
}

// ==================== Resolve Trust Chain POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostResolveAccountEndpointCommand>())
class PostResolveAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolutionService: ResolutionService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val multi = MultiValueParams.parseFormMulti(body)

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.RESOLVE, methodIsPost = true,
            params = multi.mapValues { it.value.lastOrNull().orEmpty() },
        )?.let { return Ok(it) }

        val sub = MultiValueParams.first(multi, "sub")
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val trustAnchors = MultiValueParams.all(multi, "trust_anchor")
        val entityTypes = MultiValueParams.all(multi, "entity_type")
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        return resolveChain(resolutionService, tenantId, sub, trustAnchors, entityTypes)
    }
}

// ==================== Shared Helpers ====================

private suspend fun resolveChain(
    resolutionService: ResolutionService,
    tenantId: String,
    sub: String,
    trustAnchors: List<String>,
    entityTypes: Array<String>?
): IdkResult<GenericHttpResponse, IdkError> {
    if (trustAnchors.isEmpty()) {
        return Ok(
            FederationErrorResponses.invalidTrustAnchor(
                "Missing 'trust_anchor' parameter (one or more Trust Anchor Entity Identifiers required)"
            )
        )
    }

    val result = resolutionService.getSignedResolveResponseJwt(
        tenantId = tenantId,
        sub = sub,
        trustAnchors = trustAnchors.toTypedArray(),
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
        Ok(FederationErrorResponses.fromServiceError(error))
    }
}

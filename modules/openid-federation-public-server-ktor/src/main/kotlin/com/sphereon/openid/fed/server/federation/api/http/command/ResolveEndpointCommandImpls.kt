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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveRootEndpointCommand::class)
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
            ?: return Ok(errorResponse(400, "Missing 'trust_anchor' parameter"))

        // Handle multiple entity_type parameters
        val entityTypes = request.queryParameters.entries
            .filter { it.key == "entity_type" }
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        val result = resolutionService.getSignedResolveResponseJwt(
            tenantId = tenantId,
            sub = sub,
            trustAnchor = trustAnchor,
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
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveAccountEndpointCommand::class)
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
            ?: return Ok(errorResponse(400, "Missing 'trust_anchor' parameter"))

        // Handle multiple entity_type parameters
        val entityTypes = request.queryParameters.entries
            .filter { it.key == "entity_type" }
            .mapNotNull { it.value }
            .takeIf { it.isNotEmpty() }
            ?.toTypedArray()

        val result = resolutionService.getSignedResolveResponseJwt(
            tenantId = tenantId,
            sub = sub,
            trustAnchor = trustAnchor,
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
}

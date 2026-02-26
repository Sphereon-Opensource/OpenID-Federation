package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.http.errorResponse
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.services.ResolutionService
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Custom content type for resolve response JWTs.
 */
private val ResolveResponseJwtMediaType = MediaType.Custom("application/resolve-response+jwt")

// ==================== Resolve Trust Chain (Root) Endpoint ====================

interface ResolveRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.resolve"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/resolve",
            produces = setOf(ResolveResponseJwtMediaType),
            operationId = "resolveTrustChain",
            tags = setOf("resolution"),
            summary = "Resolve trust chain for an entity"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveRootEndpointCommand::class)
class ResolveRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = ResolveRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ResolveRootEndpointCommand.ENDPOINT
), ResolveRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

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
            account = account,
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

// ==================== Resolve Trust Chain (Per Account) Endpoint ====================

interface ResolveAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.resolve"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/resolve",
            produces = setOf(ResolveResponseJwtMediaType),
            operationId = "resolveAccountTrustChain",
            tags = setOf("resolution"),
            summary = "Resolve trust chain for an entity within a specific account context"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveAccountEndpointCommand::class)
class ResolveAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val resolutionService: ResolutionService
) : HttpEndpointCommandAdapter(
    id = ResolveAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ResolveAccountEndpointCommand.ENDPOINT
), ResolveAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

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
            account = account,
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

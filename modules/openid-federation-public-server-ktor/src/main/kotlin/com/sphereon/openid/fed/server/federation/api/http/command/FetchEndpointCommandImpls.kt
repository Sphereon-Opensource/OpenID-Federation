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
import com.sphereon.openid.fed.persistence.Persistence
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateRootEndpointCommand::class)
class FetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateRootEndpointCommand.ENDPOINT
), FetchSubordinateRootEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val iss = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(errorResponse(404, "Tenant identifier not found"))

        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Subordinate statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateAccountEndpointCommand::class)
class FetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateAccountEndpointCommand.ENDPOINT
), FetchSubordinateAccountEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val iss = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(errorResponse(404, "Tenant identifier not found"))

        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Subordinate statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

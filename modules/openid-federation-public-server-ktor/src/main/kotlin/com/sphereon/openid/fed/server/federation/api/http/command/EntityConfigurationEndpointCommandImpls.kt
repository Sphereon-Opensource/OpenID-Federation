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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetEntityConfigurationEndpointCommand>())
class GetEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = GetEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetEntityConfigurationEndpointCommand.ENDPOINT
), GetEntityConfigurationEndpointCommand {

    private val entityConfigStatementQueries = Persistence.entityConfigurationStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val statement = entityConfigStatementQueries
            .findLatestByAccountId(tenantId)
            .executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAccountEntityConfigurationEndpointCommand>())
class GetAccountEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = GetAccountEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetAccountEntityConfigurationEndpointCommand.ENDPOINT
), GetAccountEntityConfigurationEndpointCommand {

    private val entityConfigStatementQueries = Persistence.entityConfigurationStatementQueries

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

        val statement = entityConfigStatementQueries
            .findLatestByAccountId(tenantId)
            .executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

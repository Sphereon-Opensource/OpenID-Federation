package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.command.entityConfiguration.FindLatestStoredEntityConfigurationJwtCommand
import com.sphereon.openid.fed.services.command.entityConfiguration.FindLatestStoredEntityConfigurationJwtArgs
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetEntityConfigurationEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(GetEntityConfigurationEndpointCommand.COMMAND_ID)
class GetEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val findStored: FindLatestStoredEntityConfigurationJwtCommand,
) : HttpEndpointCommandAdapter(
    id = GetEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetEntityConfigurationEndpointCommand.ENDPOINT
), GetEntityConfigurationEndpointCommand {


    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val found = findStored.execute(FindLatestStoredEntityConfigurationJwtArgs(tenantId))
        if (found.isErr) return Ok(errorResponse(found.error.httpStatusValue, found.error.message.defaultMessage))
        val statement = found.value.compact ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement
        ))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAccountEntityConfigurationEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(GetAccountEntityConfigurationEndpointCommand.COMMAND_ID)
class GetAccountEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val findStored: FindLatestStoredEntityConfigurationJwtCommand,
) : HttpEndpointCommandAdapter(
    id = GetAccountEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetAccountEntityConfigurationEndpointCommand.ENDPOINT
), GetAccountEntityConfigurationEndpointCommand {


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

        val found = findStored.execute(FindLatestStoredEntityConfigurationJwtArgs(tenantId))
        if (found.isErr) return Ok(errorResponse(found.error.httpStatusValue, found.error.message.defaultMessage))
        val statement = found.value.compact ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement
        ))
    }
}

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
import com.sphereon.openid.fed.services.JwkService
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = HistoricalKeysRootEndpointCommand::class)
class HistoricalKeysRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService
) : HttpEndpointCommandAdapter(
    id = HistoricalKeysRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = HistoricalKeysRootEndpointCommand.ENDPOINT
), HistoricalKeysRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = jwkService.getFederationHistoricalKeysJwt(tenantId)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/jwk-set+jwt"),
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
@ContributesBinding(SessionScope::class, boundType = HistoricalKeysAccountEndpointCommand::class)
class HistoricalKeysAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService
) : HttpEndpointCommandAdapter(
    id = HistoricalKeysAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = HistoricalKeysAccountEndpointCommand.ENDPOINT
), HistoricalKeysAccountEndpointCommand {

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

        val result = jwkService.getFederationHistoricalKeysJwt(tenantId)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/jwk-set+jwt"),
                body = result.value
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

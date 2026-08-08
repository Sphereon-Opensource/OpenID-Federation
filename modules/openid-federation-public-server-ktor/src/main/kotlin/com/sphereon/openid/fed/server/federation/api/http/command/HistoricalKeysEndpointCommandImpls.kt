package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.server.federation.api.http.auth.FederationEndpointClientAuthService
import com.sphereon.openid.fed.server.federation.api.http.auth.asAuthParams
import com.sphereon.openid.fed.server.federation.api.http.auth.enforceFederationClientAuth
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.JwkService
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<HistoricalKeysRootEndpointCommand>())
class HistoricalKeysRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.HISTORICAL_KEYS, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val result = jwkService.getFederationHistoricalKeysJwt(tenantId)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/jwk-set+jwt"),
                body = result.value
            ))
        } else {
            val error = result.error
            Ok(FederationErrorResponses.fromServiceError(error))
        }
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<HistoricalKeysAccountEndpointCommand>())
class HistoricalKeysAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.HISTORICAL_KEYS, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val result = jwkService.getFederationHistoricalKeysJwt(tenantId)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/jwk-set+jwt"),
                body = result.value
            ))
        } else {
            val error = result.error
            Ok(FederationErrorResponses.fromServiceError(error))
        }
    }
}

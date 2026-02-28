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
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.JwkService
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Custom content type for JWK Set JWTs.
 */
private val JwkSetJwtMediaType = MediaType.Custom("application/jwk-set+jwt")

// ==================== Historical Keys (Root) Endpoint ====================

interface HistoricalKeysRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.historical-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/historical-keys",
            produces = setOf(JwkSetJwtMediaType),
            operationId = "getHistoricalKeys",
            tags = setOf("keys"),
            summary = "Get historical federation keys"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = HistoricalKeysRootEndpointCommand::class)
class HistoricalKeysRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val jwkService: JwkService,
    private val accountService: AccountService
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

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = jwkService.getFederationHistoricalKeysJwt(account)

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

// ==================== Historical Keys (Per Account) Endpoint ====================

interface HistoricalKeysAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.historical-keys"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/historical-keys",
            produces = setOf(JwkSetJwtMediaType),
            operationId = "getAccountHistoricalKeys",
            tags = setOf("keys"),
            summary = "Get historical federation keys for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = HistoricalKeysAccountEndpointCommand::class)
class HistoricalKeysAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val jwkService: JwkService,
    private val accountService: AccountService
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

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = jwkService.getFederationHistoricalKeysJwt(account)

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

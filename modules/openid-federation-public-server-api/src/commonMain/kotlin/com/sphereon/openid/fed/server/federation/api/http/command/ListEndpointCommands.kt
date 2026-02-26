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
import com.sphereon.core.api.http.jsonResponse
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.services.SubordinateService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Subordinates (Root) Endpoint ====================

interface ListSubordinatesRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinates",
            tags = setOf("federation"),
            summary = "List subordinate entities"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinatesRootEndpointCommand::class)
class ListSubordinatesRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinatesRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinatesRootEndpointCommand.ENDPOINT
), ListSubordinatesRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = subordinateService.findSubordinatesByAccountAsArray(account)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== List Subordinates (Per Account) Endpoint ====================

interface ListSubordinatesAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountSubordinates",
            tags = setOf("federation"),
            summary = "List subordinate entities for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinatesAccountEndpointCommand::class)
class ListSubordinatesAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val subordinateService: SubordinateService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinatesAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinatesAccountEndpointCommand.ENDPOINT
), ListSubordinatesAccountEndpointCommand {

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

        val result = subordinateService.findSubordinatesByAccountAsArray(account)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

package com.sphereon.openid.fed.server.admin.http.command

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
import com.sphereon.openid.fed.openapi.models.CreateAuthorityHint
import com.sphereon.openid.fed.services.AuthorityHintService
import com.sphereon.openid.fed.services.mappers.toAuthorityHintsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Authority Hints Endpoint ====================

interface ListAuthorityHintsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.authorityhints.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/authority-hints",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAuthorityHints",
            tags = setOf("authority-hints"),
            summary = "List all authority hints for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListAuthorityHintsEndpointCommand::class)
class ListAuthorityHintsEndpointCommandImpl(
    execution: SessionExecution,
    private val authorityHintService: AuthorityHintService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListAuthorityHintsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListAuthorityHintsEndpointCommand.ENDPOINT
), ListAuthorityHintsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(401, "Account not found"))

        val result = authorityHintService.findByAccount(account)

        return if (result.isOk) {
            val hints = result.value.toAuthorityHintsResponse()
            Ok(jsonResponse(200, json.encodeToString(hints)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Authority Hint Endpoint ====================

interface CreateAuthorityHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.authorityhints.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/authority-hints",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Create a new authority hint"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateAuthorityHintEndpointCommand::class)
class CreateAuthorityHintEndpointCommandImpl(
    execution: SessionExecution,
    private val authorityHintService: AuthorityHintService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateAuthorityHintEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateAuthorityHintEndpointCommand.ENDPOINT
), CreateAuthorityHintEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(401, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createAuthorityHint = try {
            json.decodeFromString<CreateAuthorityHint>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = authorityHintService.createAuthorityHint(account, createAuthorityHint.identifier)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(result.value)
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Delete Authority Hint Endpoint ====================

interface DeleteAuthorityHintEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.authorityhints.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/authority-hints/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteAuthorityHint",
            tags = setOf("authority-hints"),
            summary = "Delete an authority hint by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteAuthorityHintEndpointCommand::class)
class DeleteAuthorityHintEndpointCommandImpl(
    execution: SessionExecution,
    private val authorityHintService: AuthorityHintService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteAuthorityHintEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteAuthorityHintEndpointCommand.ENDPOINT
), DeleteAuthorityHintEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(401, "Account not found"))

        val req = request.withExtractedParams(DeleteAuthorityHintEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = authorityHintService.deleteAuthorityHint(account, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

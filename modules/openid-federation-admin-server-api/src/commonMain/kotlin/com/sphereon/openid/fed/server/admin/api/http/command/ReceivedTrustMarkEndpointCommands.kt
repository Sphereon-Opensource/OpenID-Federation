package com.sphereon.openid.fed.server.admin.api.http.command

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
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.services.ReceivedTrustMarkService
import com.sphereon.openid.fed.services.mappers.toReceivedTrustMarksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Received Trust Marks Endpoint ====================

interface ListReceivedTrustMarksEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.receivedtrustmarks.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/received-trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listReceivedTrustMarks",
            tags = setOf("received-trust-marks"),
            summary = "List all received trust marks for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListReceivedTrustMarksEndpointCommand::class)
class ListReceivedTrustMarksEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListReceivedTrustMarksEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListReceivedTrustMarksEndpointCommand.ENDPOINT
), ListReceivedTrustMarksEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = receivedTrustMarkService.listReceivedTrustMarks(account)

        return if (result.isOk) {
            val trustMarks = result.value.toReceivedTrustMarksResponse()
            Ok(jsonResponse(200, json.encodeToString(trustMarks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Received Trust Mark Endpoint ====================

interface CreateReceivedTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.receivedtrustmarks.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/received-trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Create a new received trust mark"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateReceivedTrustMarkEndpointCommand::class)
class CreateReceivedTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateReceivedTrustMarkEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateReceivedTrustMarkEndpointCommand.ENDPOINT
), CreateReceivedTrustMarkEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createReceivedTrustMark = try {
            json.decodeFromString<CreateReceivedTrustMark>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = receivedTrustMarkService.createReceivedTrustMark(account, createReceivedTrustMark)

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

// ==================== Delete Received Trust Mark Endpoint ====================

interface DeleteReceivedTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.receivedtrustmarks.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/received-trust-marks/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteReceivedTrustMark",
            tags = setOf("received-trust-marks"),
            summary = "Delete a received trust mark by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteReceivedTrustMarkEndpointCommand::class)
class DeleteReceivedTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteReceivedTrustMarkEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteReceivedTrustMarkEndpointCommand.ENDPOINT
), DeleteReceivedTrustMarkEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteReceivedTrustMarkEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = receivedTrustMarkService.deleteReceivedTrustMark(account, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

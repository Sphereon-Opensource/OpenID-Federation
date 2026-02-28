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
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.services.TrustMarkService
import com.sphereon.openid.fed.services.mappers.toCreateTrustMarkResult
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toTrustMarksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Trust Marks Endpoint ====================

interface ListTrustMarksEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarks.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-marks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarks",
            tags = setOf("trust-marks"),
            summary = "List all trust marks for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListTrustMarksEndpointCommand::class)
class ListTrustMarksEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListTrustMarksEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListTrustMarksEndpointCommand.ENDPOINT
), ListTrustMarksEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = trustMarkService.getTrustMarksForAccount(account)

        return if (result.isOk) {
            val trustMarks = result.value.toTrustMarksResponse()
            Ok(jsonResponse(200, json.encodeToString(trustMarks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Trust Mark Endpoint ====================

interface CreateTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarks.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-marks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createTrustMark",
            tags = setOf("trust-marks"),
            summary = "Create a new trust mark"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateTrustMarkEndpointCommand::class)
class CreateTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateTrustMarkEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateTrustMarkEndpointCommand.ENDPOINT
), CreateTrustMarkEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createTrustMarkRequest = try {
            json.decodeFromString<CreateTrustMarkRequest>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustMarkService.createTrustMark(account, createTrustMarkRequest)

        return if (result.isOk) {
            val statusCode = if (createTrustMarkRequest.dryRun == true) 200 else 201
            Ok(GenericHttpResponse(
                statusCode = statusCode,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(result.value)
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Delete Trust Mark Endpoint ====================

interface DeleteTrustMarkEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarks.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-marks/{trustMarkId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteTrustMark",
            tags = setOf("trust-marks"),
            summary = "Delete a trust mark by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkEndpointCommand::class)
class DeleteTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteTrustMarkEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteTrustMarkEndpointCommand.ENDPOINT
), DeleteTrustMarkEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteTrustMarkEndpointCommand.ENDPOINT.pathPattern)
        val trustMarkId = req.pathParams["trustMarkId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: trustMarkId"))

        val result = trustMarkService.deleteTrustMark(account, trustMarkId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value.toCreateTrustMarkResult())))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

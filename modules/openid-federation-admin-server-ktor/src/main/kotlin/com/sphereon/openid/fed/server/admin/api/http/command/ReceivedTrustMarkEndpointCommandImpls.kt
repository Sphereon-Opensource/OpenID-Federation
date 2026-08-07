package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.services.ReceivedTrustMarkService
import com.sphereon.openid.fed.services.mappers.toReceivedTrustMarksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Received Trust Marks Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListReceivedTrustMarksEndpointCommand>())
class ListReceivedTrustMarksEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = receivedTrustMarkService.listReceivedTrustMarks(tenantId)

        return if (result.isOk) {
            val trustMarks = result.value.toReceivedTrustMarksResponse()
            Ok(jsonResponse(200, json.encodeToString(trustMarks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Received Trust Mark Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateReceivedTrustMarkEndpointCommand>())
class CreateReceivedTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createReceivedTrustMark = try {
            json.decodeFromString<CreateReceivedTrustMark>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = receivedTrustMarkService.createReceivedTrustMark(tenantId, createReceivedTrustMark)

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

// ==================== Delete Received Trust Mark Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteReceivedTrustMarkEndpointCommand>())
class DeleteReceivedTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteReceivedTrustMarkEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = receivedTrustMarkService.deleteReceivedTrustMark(tenantId, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

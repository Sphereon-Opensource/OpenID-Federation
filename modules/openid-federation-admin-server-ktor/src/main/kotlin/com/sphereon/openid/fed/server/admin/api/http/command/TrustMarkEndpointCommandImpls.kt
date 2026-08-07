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
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.services.TrustMarkService
import com.sphereon.openid.fed.services.mappers.toCreateTrustMarkResult
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toTrustMarksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Trust Marks Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListTrustMarksEndpointCommand>())
class ListTrustMarksEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = trustMarkService.getTrustMarksForAccount(tenantId)

        return if (result.isOk) {
            val trustMarks = result.value.toTrustMarksResponse()
            Ok(jsonResponse(200, json.encodeToString(trustMarks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Trust Mark Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateTrustMarkEndpointCommand>())
class CreateTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val trustMarkService: TrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createTrustMarkRequest = try {
            json.decodeFromString<CreateTrustMarkRequest>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustMarkService.createTrustMark(tenantId, createTrustMarkRequest, System.currentTimeMillis())

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

// ==================== Delete Trust Mark Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteTrustMarkEndpointCommand>())
class DeleteTrustMarkEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val trustMarkService: TrustMarkService,
    private val tenantContextResolver: TenantContextResolver,
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
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteTrustMarkEndpointCommand.ENDPOINT.pathPattern)
        val trustMarkId = req.pathParams["trustMarkId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: trustMarkId"))

        val result = trustMarkService.deleteTrustMark(tenantId, trustMarkId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value.toCreateTrustMarkResult())))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

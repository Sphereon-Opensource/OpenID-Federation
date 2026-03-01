package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.errorResponse
import com.sphereon.core.api.http.jsonResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.CreateSubordinateConstraints
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.services.SubordinateConstraintService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== Get Subordinate Constraints Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSubordinateConstraintsEndpointCommand::class)
class GetSubordinateConstraintsEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateConstraintService: SubordinateConstraintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetSubordinateConstraintsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetSubordinateConstraintsEndpointCommand.ENDPOINT
), GetSubordinateConstraintsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(GetSubordinateConstraintsEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateConstraintService.getConstraints(tenantId, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Set Subordinate Constraints Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = SetSubordinateConstraintsEndpointCommand::class)
class SetSubordinateConstraintsEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateConstraintService: SubordinateConstraintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = SetSubordinateConstraintsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = SetSubordinateConstraintsEndpointCommand.ENDPOINT
), SetSubordinateConstraintsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(SetSubordinateConstraintsEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createConstraints = try {
            json.decodeFromString<CreateSubordinateConstraints>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val constraints = Constraints(
            maxPathLength = createConstraints.maxPathLength,
            namingConstraints = createConstraints.namingConstraints,
            allowedEntityTypes = createConstraints.allowedEntityTypes
        )

        val result = subordinateConstraintService.setConstraints(tenantId, subordinateId, constraints)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Delete Subordinate Constraints Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateConstraintsEndpointCommand::class)
class DeleteSubordinateConstraintsEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateConstraintService: SubordinateConstraintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteSubordinateConstraintsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteSubordinateConstraintsEndpointCommand.ENDPOINT
), DeleteSubordinateConstraintsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteSubordinateConstraintsEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateConstraintService.deleteConstraints(tenantId, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

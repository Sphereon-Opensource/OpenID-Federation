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
import com.sphereon.openid.fed.openapi.models.CreateMetadataPolicy
import com.sphereon.openid.fed.openapi.models.MetadataPolicyResponse
import com.sphereon.openid.fed.server.admin.api.mappers.toJsonElement
import com.sphereon.openid.fed.services.MetadataPolicyService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Metadata Policies Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListMetadataPoliciesEndpointCommand>())
class ListMetadataPoliciesEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataPolicyService: MetadataPolicyService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListMetadataPoliciesEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListMetadataPoliciesEndpointCommand.ENDPOINT
), ListMetadataPoliciesEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = metadataPolicyService.findByAccount(tenantId)

        return if (result.isOk) {
            val response = MetadataPolicyResponse(result.value)
            Ok(jsonResponse(200, json.encodeToString(response)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Metadata Policy Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateMetadataPolicyEndpointCommand>())
class CreateMetadataPolicyEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataPolicyService: MetadataPolicyService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateMetadataPolicyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateMetadataPolicyEndpointCommand.ENDPOINT
), CreateMetadataPolicyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createMetadataPolicy = try {
            json.decodeFromString<CreateMetadataPolicy>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = metadataPolicyService.createPolicy(
            tenantId,
            createMetadataPolicy.key,
            createMetadataPolicy.policy.toJsonElement()
        )

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

// ==================== Delete Metadata Policy Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteMetadataPolicyEndpointCommand>())
class DeleteMetadataPolicyEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataPolicyService: MetadataPolicyService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteMetadataPolicyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteMetadataPolicyEndpointCommand.ENDPOINT
), DeleteMetadataPolicyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteMetadataPolicyEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = metadataPolicyService.deletePolicy(tenantId, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

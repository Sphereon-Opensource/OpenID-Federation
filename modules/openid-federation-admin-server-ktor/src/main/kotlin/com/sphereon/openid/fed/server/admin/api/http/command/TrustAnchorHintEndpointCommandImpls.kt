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
import com.sphereon.openid.fed.openapi.models.CreateTrustAnchorHint
import com.sphereon.openid.fed.services.TrustAnchorHintService
import com.sphereon.openid.fed.services.mappers.toTrustAnchorHintsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Trust Anchor Hints Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListTrustAnchorHintsEndpointCommand::class)
class ListTrustAnchorHintsEndpointCommandImpl(
    execution: SessionExecution,
    private val trustAnchorHintService: TrustAnchorHintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListTrustAnchorHintsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListTrustAnchorHintsEndpointCommand.ENDPOINT
), ListTrustAnchorHintsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = trustAnchorHintService.findByAccount(tenantId)

        return if (result.isOk) {
            val hints = result.value.toTrustAnchorHintsResponse()
            Ok(jsonResponse(200, json.encodeToString(hints)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Trust Anchor Hint Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateTrustAnchorHintEndpointCommand::class)
class CreateTrustAnchorHintEndpointCommandImpl(
    execution: SessionExecution,
    private val trustAnchorHintService: TrustAnchorHintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateTrustAnchorHintEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateTrustAnchorHintEndpointCommand.ENDPOINT
), CreateTrustAnchorHintEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createTrustAnchorHint = try {
            json.decodeFromString<CreateTrustAnchorHint>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustAnchorHintService.createTrustAnchorHint(tenantId, createTrustAnchorHint.identifier)

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

// ==================== Delete Trust Anchor Hint Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustAnchorHintEndpointCommand::class)
class DeleteTrustAnchorHintEndpointCommandImpl(
    execution: SessionExecution,
    private val trustAnchorHintService: TrustAnchorHintService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteTrustAnchorHintEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteTrustAnchorHintEndpointCommand.ENDPOINT
), DeleteTrustAnchorHintEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteTrustAnchorHintEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = trustAnchorHintService.deleteTrustAnchorHint(tenantId, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

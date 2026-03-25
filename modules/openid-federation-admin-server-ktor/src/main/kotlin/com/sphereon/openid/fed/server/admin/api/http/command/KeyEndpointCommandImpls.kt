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
import com.sphereon.openid.fed.openapi.models.CreateKey
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toAccountJwksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Keys Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListKeysEndpointCommand>())
class ListKeysEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListKeysEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListKeysEndpointCommand.ENDPOINT
), ListKeysEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = jwkService.getKeys(tenantId, includeRevoked = false)

        return if (result.isOk) {
            val keys = result.value.toAccountJwksResponse()
            Ok(jsonResponse(200, json.encodeToString(keys)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateKeyEndpointCommand>())
class CreateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateKeyEndpointCommand.ENDPOINT
), CreateKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val createKey = if (request.body.isNullOrBlank()) {
            CreateKey()
        } else {
            try {
                json.decodeFromString<CreateKey>(request.body!!)
            } catch (e: Exception) {
                return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
            }
        }

        val result = jwkService.createKey(tenantId, CreateKeyArgs.fromModel(createKey))

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

// ==================== Revoke Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RevokeKeyEndpointCommand>())
class RevokeKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = RevokeKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = RevokeKeyEndpointCommand.ENDPOINT
), RevokeKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(RevokeKeyEndpointCommand.ENDPOINT.pathPattern)
        val keyId = req.pathParams["keyId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: keyId"))

        val reason = request.queryParameters["reason"]

        val result = jwkService.revokeKey(tenantId, keyId, reason)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

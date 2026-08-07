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
import com.sphereon.openid.fed.openapi.models.PublishStatementRequest
import com.sphereon.openid.fed.services.EntityConfigurationStatementService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Get Entity Statement Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetEntityStatementEndpointCommand>())
class GetEntityStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val entityConfigurationStatementService: EntityConfigurationStatementService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetEntityStatementEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetEntityStatementEndpointCommand.ENDPOINT
), GetEntityStatementEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = entityConfigurationStatementService.findByAccount(tenantId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Publish Entity Statement Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PublishEntityStatementEndpointCommand>())
class PublishEntityStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val entityConfigurationStatementService: EntityConfigurationStatementService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PublishEntityStatementEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PublishEntityStatementEndpointCommand.ENDPOINT
), PublishEntityStatementEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = if (request.body.isNullOrBlank()) null else try {
            json.decodeFromString<PublishStatementRequest>(request.body!!)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = entityConfigurationStatementService.publishByAccount(
            tenantId,
            dryRun = body?.dryRun,
            kmsKeyRef = body?.kmsKeyRef,
            kid = body?.kid
        )

        return if (result.isOk) {
            val statusCode = if (body?.dryRun == true) 200 else 201
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

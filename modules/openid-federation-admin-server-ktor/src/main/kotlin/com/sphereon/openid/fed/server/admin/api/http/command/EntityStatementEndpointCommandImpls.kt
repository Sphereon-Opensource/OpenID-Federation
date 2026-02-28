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
import com.sphereon.openid.fed.openapi.models.PublishStatementRequest
import com.sphereon.openid.fed.services.EntityConfigurationStatementService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== Get Entity Statement Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetEntityStatementEndpointCommand::class)
class GetEntityStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val entityConfigurationStatementService: EntityConfigurationStatementService,
    private val accountResolver: AccountResolver,
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

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = entityConfigurationStatementService.findByAccount(account)

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
@ContributesBinding(SessionScope::class, boundType = PublishEntityStatementEndpointCommand::class)
class PublishEntityStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val entityConfigurationStatementService: EntityConfigurationStatementService,
    private val accountResolver: AccountResolver,
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
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = if (request.body.isNullOrBlank()) null else try {
            json.decodeFromString<PublishStatementRequest>(request.body!!)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = entityConfigurationStatementService.publishByAccount(
            account,
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

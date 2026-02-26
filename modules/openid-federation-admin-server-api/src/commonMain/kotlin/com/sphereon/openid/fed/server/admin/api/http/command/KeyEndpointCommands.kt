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
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.openapi.models.CreateKey
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toAccountJwksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Keys Endpoint ====================

interface ListKeysEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.keys.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/keys",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listKeys",
            tags = setOf("keys"),
            summary = "List all keys for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListKeysEndpointCommand::class)
class ListKeysEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListKeysEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListKeysEndpointCommand.ENDPOINT
), ListKeysEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = jwkService.getKeys(account)

        return if (result.isOk) {
            val keys = result.value.toAccountJwksResponse()
            Ok(jsonResponse(200, json.encodeToString(keys)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Key Endpoint ====================

interface CreateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.keys.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/keys",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createKey",
            tags = setOf("keys"),
            summary = "Create a new key for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateKeyEndpointCommand::class)
class CreateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateKeyEndpointCommand.ENDPOINT
), CreateKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val createKey = if (request.body.isNullOrBlank()) {
            CreateKey()
        } else {
            try {
                json.decodeFromString<CreateKey>(request.body!!)
            } catch (e: Exception) {
                return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
            }
        }

        val result = jwkService.createKey(account, CreateKeyArgs.fromModel(createKey))

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

// ==================== Revoke Key Endpoint ====================

interface RevokeKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.keys.revoke"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/keys/{keyId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "revokeKey",
            tags = setOf("keys"),
            summary = "Revoke a key by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = RevokeKeyEndpointCommand::class)
class RevokeKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = RevokeKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = RevokeKeyEndpointCommand.ENDPOINT
), RevokeKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(RevokeKeyEndpointCommand.ENDPOINT.pathPattern)
        val keyId = req.pathParams["keyId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: keyId"))

        val reason = request.queryParameters["reason"]

        val result = jwkService.revokeKey(account, keyId, reason)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

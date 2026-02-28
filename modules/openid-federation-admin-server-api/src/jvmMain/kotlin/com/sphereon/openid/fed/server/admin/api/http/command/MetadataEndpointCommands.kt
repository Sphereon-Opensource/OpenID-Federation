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
import com.sphereon.openid.fed.openapi.models.CreateMetadata
import com.sphereon.openid.fed.server.admin.api.mappers.toJsonElement
import com.sphereon.openid.fed.services.MetadataService
import com.sphereon.openid.fed.services.mappers.toMetadataResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListMetadataEndpointCommand::class)
class ListMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataService: MetadataService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListMetadataEndpointCommand.ENDPOINT
), ListMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = metadataService.findByAccount(account)

        return if (result.isOk) {
            val metadata = result.value.toMetadataResponse()
            Ok(jsonResponse(200, json.encodeToString(metadata)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateMetadataEndpointCommand::class)
class CreateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataService: MetadataService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateMetadataEndpointCommand.ENDPOINT
), CreateMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createMetadata = try {
            json.decodeFromString<CreateMetadata>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = metadataService.createMetadata(
            account,
            createMetadata.key,
            createMetadata.metadata.toJsonElement()
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

// ==================== Delete Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteMetadataEndpointCommand::class)
class DeleteMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val metadataService: MetadataService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteMetadataEndpointCommand.ENDPOINT
), DeleteMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteMetadataEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = metadataService.deleteMetadata(account, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

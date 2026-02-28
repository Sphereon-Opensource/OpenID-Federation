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
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.openapi.models.CreateMetadata
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.PublishStatementRequest
import com.sphereon.openid.fed.services.SubordinateService
import com.sphereon.openid.fed.services.mappers.toSubordinateJwksResponse
import com.sphereon.openid.fed.services.mappers.toSubordinateMetadataResponse
import com.sphereon.openid.fed.services.mappers.toSubordinatesResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Subordinates Endpoint ====================

interface ListSubordinatesEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinates",
            tags = setOf("subordinates"),
            summary = "List all subordinates for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinatesEndpointCommand::class)
class ListSubordinatesEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinatesEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinatesEndpointCommand.ENDPOINT
), ListSubordinatesEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = subordinateService.findSubordinatesByAccount(account)

        return if (result.isOk) {
            val subordinates = result.value.toSubordinatesResponse()
            Ok(jsonResponse(200, json.encodeToString(subordinates)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Endpoint ====================

interface CreateSubordinateEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinate",
            tags = setOf("subordinates"),
            summary = "Create a new subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateEndpointCommand::class)
class CreateSubordinateEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateSubordinateEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateSubordinateEndpointCommand.ENDPOINT
), CreateSubordinateEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createSubordinate = try {
            json.decodeFromString<CreateSubordinate>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.createSubordinate(account, createSubordinate)

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

// ==================== Delete Subordinate Endpoint ====================

interface DeleteSubordinateEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinate",
            tags = setOf("subordinates"),
            summary = "Delete a subordinate by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateEndpointCommand::class)
class DeleteSubordinateEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteSubordinateEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteSubordinateEndpointCommand.ENDPOINT
), DeleteSubordinateEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteSubordinateEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.deleteSubordinate(account, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== List Subordinate Keys Endpoint ====================

interface ListSubordinateKeysEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.keys.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/jwks",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinateKeys",
            tags = setOf("subordinates", "keys"),
            summary = "List keys for a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinateKeysEndpointCommand::class)
class ListSubordinateKeysEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinateKeysEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinateKeysEndpointCommand.ENDPOINT
), ListSubordinateKeysEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(ListSubordinateKeysEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.getSubordinateJwks(account, subordinateId)

        return if (result.isOk) {
            val jwks = result.value.toSubordinateJwksResponse()
            Ok(jsonResponse(200, json.encodeToString(jwks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Key Endpoint ====================

interface CreateSubordinateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.keys.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/jwks",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinateKey",
            tags = setOf("subordinates", "keys"),
            summary = "Add a key to a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateKeyEndpointCommand::class)
class CreateSubordinateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateSubordinateKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateSubordinateKeyEndpointCommand.ENDPOINT
), CreateSubordinateKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(CreateSubordinateKeyEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val jwk = try {
            json.decodeFromString<Jwk>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.createSubordinateJwk(account, subordinateId, jwk)

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

// ==================== Delete Subordinate Key Endpoint ====================

interface DeleteSubordinateKeyEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.keys.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/jwks/{jwkId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinateKey",
            tags = setOf("subordinates", "keys"),
            summary = "Remove a key from a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateKeyEndpointCommand::class)
class DeleteSubordinateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteSubordinateKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteSubordinateKeyEndpointCommand.ENDPOINT
), DeleteSubordinateKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteSubordinateKeyEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))
        val jwkId = req.pathParams["jwkId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: jwkId"))

        val result = subordinateService.deleteSubordinateJwk(account, subordinateId, jwkId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Get Subordinate Statement Endpoint ====================

interface GetSubordinateStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.statement.get"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/statement",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getSubordinateStatement",
            tags = setOf("subordinates", "statements"),
            summary = "Get the statement for a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSubordinateStatementEndpointCommand::class)
class GetSubordinateStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetSubordinateStatementEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetSubordinateStatementEndpointCommand.ENDPOINT
), GetSubordinateStatementEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(GetSubordinateStatementEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.getSubordinateStatement(account, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Publish Subordinate Statement Endpoint ====================

interface PublishSubordinateStatementEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.statement.publish"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/statement",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "publishSubordinateStatement",
            tags = setOf("subordinates", "statements"),
            summary = "Publish a subordinate statement"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PublishSubordinateStatementEndpointCommand::class)
class PublishSubordinateStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PublishSubordinateStatementEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PublishSubordinateStatementEndpointCommand.ENDPOINT
), PublishSubordinateStatementEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(PublishSubordinateStatementEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = if (request.body.isNullOrBlank()) null else try {
            json.decodeFromString<PublishStatementRequest>(request.body!!)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.publishSubordinateStatement(
            account = account,
            id = subordinateId,
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

// ==================== List Subordinate Metadata Endpoint ====================

interface ListSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.metadata.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/subordinates/{subordinateId}/metadata",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "List metadata for a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListSubordinateMetadataEndpointCommand::class)
class ListSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListSubordinateMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListSubordinateMetadataEndpointCommand.ENDPOINT
), ListSubordinateMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(ListSubordinateMetadataEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.findSubordinateMetadata(account, subordinateId)

        return if (result.isOk) {
            val metadata = result.value.toList().toSubordinateMetadataResponse()
            Ok(jsonResponse(200, json.encodeToString(metadata)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Metadata Endpoint ====================

interface CreateSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.metadata.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/subordinates/{subordinateId}/metadata",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "Add metadata to a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateMetadataEndpointCommand::class)
class CreateSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateSubordinateMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateSubordinateMetadataEndpointCommand.ENDPOINT
), CreateSubordinateMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(CreateSubordinateMetadataEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createMetadata = try {
            json.decodeFromString<CreateMetadata>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.createMetadata(
            account = account,
            subordinateId = subordinateId,
            key = createMetadata.key,
            metadata = createMetadata.metadata
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

// ==================== Delete Subordinate Metadata Endpoint ====================

interface DeleteSubordinateMetadataEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.subordinates.metadata.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/subordinates/{subordinateId}/metadata/{metadataId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteSubordinateMetadata",
            tags = setOf("subordinates", "metadata"),
            summary = "Remove metadata from a subordinate"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateMetadataEndpointCommand::class)
class DeleteSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteSubordinateMetadataEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteSubordinateMetadataEndpointCommand.ENDPOINT
), DeleteSubordinateMetadataEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteSubordinateMetadataEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))
        val metadataId = req.pathParams["metadataId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: metadataId"))

        val result = subordinateService.deleteSubordinateMetadata(account, subordinateId, metadataId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

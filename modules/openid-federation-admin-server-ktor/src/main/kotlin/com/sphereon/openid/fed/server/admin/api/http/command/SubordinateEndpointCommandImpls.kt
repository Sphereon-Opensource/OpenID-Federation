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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Subordinates Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinatesEndpointCommand>())
class ListSubordinatesEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = subordinateService.findSubordinatesByAccount(tenantId)

        return if (result.isOk) {
            val subordinates = result.value.toSubordinatesResponse()
            Ok(jsonResponse(200, json.encodeToString(subordinates)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateSubordinateEndpointCommand>())
class CreateSubordinateEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createSubordinate = try {
            json.decodeFromString<CreateSubordinate>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.createSubordinate(tenantId, createSubordinate)

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

// ==================== Delete Subordinate Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteSubordinateEndpointCommand>())
class DeleteSubordinateEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteSubordinateEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.deleteSubordinate(tenantId, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== List Subordinate Keys Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinateKeysEndpointCommand>())
class ListSubordinateKeysEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(ListSubordinateKeysEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.getSubordinateJwks(tenantId, subordinateId)

        return if (result.isOk) {
            val jwks = result.value.toSubordinateJwksResponse()
            Ok(jsonResponse(200, json.encodeToString(jwks)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateSubordinateKeyEndpointCommand>())
class CreateSubordinateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(CreateSubordinateKeyEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val jwk = try {
            json.decodeFromString<Jwk>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.createSubordinateJwk(tenantId, subordinateId, jwk)

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

// ==================== Delete Subordinate Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteSubordinateKeyEndpointCommand>())
class DeleteSubordinateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteSubordinateKeyEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))
        val jwkId = req.pathParams["jwkId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: jwkId"))

        val result = subordinateService.deleteSubordinateJwk(tenantId, subordinateId, jwkId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Get Subordinate Statement Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetSubordinateStatementEndpointCommand>())
class GetSubordinateStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(GetSubordinateStatementEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.getSubordinateStatement(tenantId, subordinateId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Publish Subordinate Statement Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PublishSubordinateStatementEndpointCommand>())
class PublishSubordinateStatementEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(PublishSubordinateStatementEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val body = if (request.body.isNullOrBlank()) null else try {
            json.decodeFromString<PublishStatementRequest>(request.body!!)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = subordinateService.publishSubordinateStatement(
            tenantId = tenantId,
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

// ==================== List Subordinate Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinateMetadataEndpointCommand>())
class ListSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(ListSubordinateMetadataEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))

        val result = subordinateService.findSubordinateMetadata(tenantId, subordinateId)

        return if (result.isOk) {
            val metadata = result.value.toList().toSubordinateMetadataResponse()
            Ok(jsonResponse(200, json.encodeToString(metadata)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Subordinate Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateSubordinateMetadataEndpointCommand>())
class CreateSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

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
            tenantId = tenantId,
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

// ==================== Delete Subordinate Metadata Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteSubordinateMetadataEndpointCommand>())
class DeleteSubordinateMetadataEndpointCommandImpl(
    execution: SessionExecution,
    private val subordinateService: SubordinateService,
    private val tenantContextResolver: TenantContextResolver,
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

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(DeleteSubordinateMetadataEndpointCommand.ENDPOINT.pathPattern)
        val subordinateId = req.pathParams["subordinateId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: subordinateId"))
        val metadataId = req.pathParams["metadataId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: metadataId"))

        val result = subordinateService.deleteSubordinateMetadata(tenantId, subordinateId, metadataId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

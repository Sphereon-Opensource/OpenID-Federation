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
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkTypeIssuerRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkIssuer
import com.sphereon.openid.fed.openapi.models.TrustMarkIssuersResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkTypesResponse
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer as PersistenceTrustMarkIssuer
import com.sphereon.openid.fed.services.TrustMarkService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== List Trust Mark Types Endpoint ====================

interface ListTrustMarkTypesEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarkTypes",
            tags = setOf("trust-marks"),
            summary = "List all trust mark types for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListTrustMarkTypesEndpointCommand::class)
class ListTrustMarkTypesEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListTrustMarkTypesEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListTrustMarkTypesEndpointCommand.ENDPOINT
), ListTrustMarkTypesEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = trustMarkService.findAllByAccount(account)

        return if (result.isOk) {
            val response = TrustMarkTypesResponse(trustMarkTypes = result.value)
            Ok(jsonResponse(200, json.encodeToString(response)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Trust Mark Type Endpoint ====================

interface CreateTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Create a new trust mark type"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateTrustMarkTypeEndpointCommand::class)
class CreateTrustMarkTypeEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateTrustMarkTypeEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateTrustMarkTypeEndpointCommand.ENDPOINT
), CreateTrustMarkTypeEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createRequest = try {
            json.decodeFromString<CreateTrustMarkType>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustMarkService.createTrustMarkType(account, createRequest)

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

// ==================== Get Trust Mark Type Endpoint ====================

interface GetTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.get"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark type by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkTypeEndpointCommand::class)
class GetTrustMarkTypeEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkTypeEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkTypeEndpointCommand.ENDPOINT
), GetTrustMarkTypeEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(GetTrustMarkTypeEndpointCommand.ENDPOINT.pathPattern)
        val typeId = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = trustMarkService.findById(account, typeId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Delete Trust Mark Type Endpoint ====================

interface DeleteTrustMarkTypeEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteTrustMarkType",
            tags = setOf("trust-marks"),
            summary = "Delete a trust mark type by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkTypeEndpointCommand::class)
class DeleteTrustMarkTypeEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteTrustMarkTypeEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteTrustMarkTypeEndpointCommand.ENDPOINT
), DeleteTrustMarkTypeEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteTrustMarkTypeEndpointCommand.ENDPOINT.pathPattern)
        val typeId = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = trustMarkService.deleteTrustMarkType(account, typeId)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Get Trust Mark Type Issuers Endpoint ====================

interface GetTrustMarkTypeIssuersEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.issuers.get"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-types/{id}/issuers",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getTrustMarkTypeIssuers",
            tags = setOf("trust-marks"),
            summary = "Get issuers for a trust mark type"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkTypeIssuersEndpointCommand::class)
class GetTrustMarkTypeIssuersEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkTypeIssuersEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkTypeIssuersEndpointCommand.ENDPOINT
), GetTrustMarkTypeIssuersEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(GetTrustMarkTypeIssuersEndpointCommand.ENDPOINT.pathPattern)
        val typeId = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = trustMarkService.getIssuersForTrustMarkType(account, typeId)

        return if (result.isOk) {
            val issuers = result.value.map { it.toApiModel() }
            val response = TrustMarkIssuersResponse(issuers = issuers)
            Ok(jsonResponse(200, json.encodeToString(TrustMarkIssuersResponse.serializer(), response)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Add Trust Mark Type Issuer Endpoint ====================

interface AddTrustMarkTypeIssuerEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.issuers.add"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-types/{id}/issuers",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "addTrustMarkTypeIssuer",
            tags = setOf("trust-marks"),
            summary = "Add an issuer to a trust mark type"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = AddTrustMarkTypeIssuerEndpointCommand::class)
class AddTrustMarkTypeIssuerEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = AddTrustMarkTypeIssuerEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = AddTrustMarkTypeIssuerEndpointCommand.ENDPOINT
), AddTrustMarkTypeIssuerEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(AddTrustMarkTypeIssuerEndpointCommand.ENDPOINT.pathPattern)
        val typeId = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val issuerRequest = try {
            json.decodeFromString<CreateTrustMarkTypeIssuerRequest>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val issuerIdentifier = issuerRequest.identifier

        val result = trustMarkService.addIssuerToTrustMarkType(account, typeId, issuerIdentifier)

        return if (result.isOk) {
            val apiModel = result.value.toApiModel()
            Ok(GenericHttpResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(TrustMarkIssuer.serializer(), apiModel)
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

/**
 * Extension function to convert persistence TrustMarkIssuer to OpenAPI TrustMarkIssuer.
 */
private fun PersistenceTrustMarkIssuer.toApiModel(): TrustMarkIssuer = TrustMarkIssuer(
    id = this.id,
    trustMarkTypeId = this.trust_mark_type_id,
    issuer = this.issuer_identifier,
    createdAt = this.created_at?.toString(),
    deletedAt = this.deleted_at?.toString()
)

// ==================== Remove Trust Mark Type Issuer Endpoint ====================

interface RemoveTrustMarkTypeIssuerEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.trustmarktypes.issuers.remove"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/trust-mark-types/{id}/issuers/{issuerId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "removeTrustMarkTypeIssuer",
            tags = setOf("trust-marks"),
            summary = "Remove an issuer from a trust mark type by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = RemoveTrustMarkTypeIssuerEndpointCommand::class)
class RemoveTrustMarkTypeIssuerEndpointCommandImpl(
    execution: SessionExecution,
    private val trustMarkService: TrustMarkService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = RemoveTrustMarkTypeIssuerEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = RemoveTrustMarkTypeIssuerEndpointCommand.ENDPOINT
), RemoveTrustMarkTypeIssuerEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(RemoveTrustMarkTypeIssuerEndpointCommand.ENDPOINT.pathPattern)
        val typeId = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))
        val issuerId = req.pathParams["issuerId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: issuerId"))

        val result = trustMarkService.removeIssuerFromTrustMarkType(account, typeId, issuerId)

        return if (result.isOk) {
            val apiModel = result.value.toApiModel()
            Ok(jsonResponse(200, json.encodeToString(TrustMarkIssuer.serializer(), apiModel)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

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
import com.sphereon.openid.fed.openapi.models.CreateCrit
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity
import com.sphereon.openid.fed.services.CriticalClaimService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Response model for critical claim operations.
 */
@Serializable
data class CritResponse(
    val id: String,
    val accountId: String,
    val claim: String
)

/**
 * Extension function to convert a CritEntity to a CritResponse.
 */
fun CritEntity.toCritResponse(): CritResponse = CritResponse(
    id = id,
    accountId = account_id,
    claim = claim
)

// ==================== List Critical Claims Endpoint ====================

interface ListCriticalClaimsEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.criticalclaims.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/crits",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listCriticalClaims",
            tags = setOf("critical-claims"),
            summary = "List all critical claims for the current account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListCriticalClaimsEndpointCommand::class)
class ListCriticalClaimsEndpointCommandImpl(
    execution: SessionExecution,
    private val criticalClaimService: CriticalClaimService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListCriticalClaimsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListCriticalClaimsEndpointCommand.ENDPOINT
), ListCriticalClaimsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = criticalClaimService.findByAccount(account)

        return if (result.isOk) {
            val crits = result.value.map { it.toCritResponse() }
            Ok(jsonResponse(200, json.encodeToString(crits)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Critical Claim Endpoint ====================

interface CreateCriticalClaimEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.criticalclaims.create"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/crits",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "createCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Create a new critical claim"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateCriticalClaimEndpointCommand::class)
class CreateCriticalClaimEndpointCommandImpl(
    execution: SessionExecution,
    private val criticalClaimService: CriticalClaimService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateCriticalClaimEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateCriticalClaimEndpointCommand.ENDPOINT
), CreateCriticalClaimEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createCrit = try {
            json.decodeFromString<CreateCrit>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = criticalClaimService.create(account, createCrit.claim)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(result.value.toCritResponse())
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Delete Critical Claim Endpoint ====================

interface DeleteCriticalClaimEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.admin.criticalclaims.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/crits/{id}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteCriticalClaim",
            tags = setOf("critical-claims"),
            summary = "Delete a critical claim by ID"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteCriticalClaimEndpointCommand::class)
class DeleteCriticalClaimEndpointCommandImpl(
    execution: SessionExecution,
    private val criticalClaimService: CriticalClaimService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteCriticalClaimEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteCriticalClaimEndpointCommand.ENDPOINT
), DeleteCriticalClaimEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val req = request.withExtractedParams(DeleteCriticalClaimEndpointCommand.ENDPOINT.pathPattern)
        val id = req.pathParams["id"]
            ?: return Ok(errorResponse(400, "Missing path parameter: id"))

        val result = criticalClaimService.delete(account, id)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value.toCritResponse())))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

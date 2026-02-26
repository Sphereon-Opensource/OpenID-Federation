package com.sphereon.openid.fed.server.federation.api.http.command

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
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusResponse
import com.sphereon.openid.fed.services.TrustMarkService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Custom content type for trust marks (JWTs).
 */
private val TrustMarkJwtMediaType = MediaType.Custom("application/trust-mark+jwt")

// ==================== Trust Mark Status (Root) Endpoint ====================

interface TrustMarkStatusRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/trust-mark-status",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "checkTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkStatusRootEndpointCommand::class)
class TrustMarkStatusRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkStatusRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkStatusRootEndpointCommand.ENDPOINT
), TrustMarkStatusRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val statusRequest = try {
            json.decodeFromString<TrustMarkStatusRequest>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustMarkService.getTrustMarkStatus(account, statusRequest)

        return if (result.isOk) {
            val response = TrustMarkStatusResponse(active = result.value)
            Ok(jsonResponse(200, json.encodeToString(response)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Trust Mark Status (Per Account) Endpoint ====================

interface TrustMarkStatusAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.trust-mark-status"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/{username}/trust-mark-status",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "checkAccountTrustMarkStatus",
            tags = setOf("trust-marks"),
            summary = "Check trust mark status for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkStatusAccountEndpointCommand::class)
class TrustMarkStatusAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkStatusAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkStatusAccountEndpointCommand.ENDPOINT
), TrustMarkStatusAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val statusRequest = try {
            json.decodeFromString<TrustMarkStatusRequest>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = trustMarkService.getTrustMarkStatus(account, statusRequest)

        return if (result.isOk) {
            val response = TrustMarkStatusResponse(active = result.value)
            Ok(jsonResponse(200, json.encodeToString(response)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Trust Mark List (Root) Endpoint ====================

interface TrustMarkListRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark-list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listTrustMarkedSubordinates",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkListRootEndpointCommand::class)
class TrustMarkListRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkListRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkListRootEndpointCommand.ENDPOINT
), TrustMarkListRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val trustMarkId = request.queryParameters["trust_mark_id"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_id' parameter"))
        val sub = request.queryParameters["sub"]

        val listRequest = TrustMarkListRequest(
            trustMarkId = trustMarkId,
            sub = sub
        )

        val result = trustMarkService.getTrustMarkedSubs(account, listRequest)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Trust Mark List (Per Account) Endpoint ====================

interface TrustMarkListAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.trust-mark-list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/trust-mark-list",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listAccountTrustMarkedSubordinates",
            tags = setOf("trust-marks"),
            summary = "List subordinates with a specific trust mark for an account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkListAccountEndpointCommand::class)
class TrustMarkListAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkListAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkListAccountEndpointCommand.ENDPOINT
), TrustMarkListAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

        val trustMarkId = request.queryParameters["trust_mark_id"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_id' parameter"))
        val sub = request.queryParameters["sub"]

        val listRequest = TrustMarkListRequest(
            trustMarkId = trustMarkId,
            sub = sub
        )

        val result = trustMarkService.getTrustMarkedSubs(account, listRequest)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Get Trust Mark (Root) Endpoint ====================

interface GetTrustMarkRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/trust-mark",
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getTrustMark",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkRootEndpointCommand::class)
class GetTrustMarkRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkRootEndpointCommand.ENDPOINT
), GetTrustMarkRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val trustMarkId = request.queryParameters["trust_mark_id"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_id' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        val trustMarkRequest = TrustMarkRequest(
            trustMarkId = trustMarkId,
            sub = sub
        )

        val result = trustMarkService.getTrustMark(account, trustMarkRequest)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/trust-mark+jwt"),
                body = result.value
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Get Trust Mark (Per Account) Endpoint ====================

interface GetTrustMarkAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.trust-mark"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/trust-mark",
            produces = setOf(TrustMarkJwtMediaType),
            operationId = "getAccountTrustMark",
            tags = setOf("trust-marks"),
            summary = "Get a trust mark JWT for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkAccountEndpointCommand::class)
class GetTrustMarkAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkAccountEndpointCommand.ENDPOINT
), GetTrustMarkAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

        val trustMarkId = request.queryParameters["trust_mark_id"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_id' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        val trustMarkRequest = TrustMarkRequest(
            trustMarkId = trustMarkId,
            sub = sub
        )

        val result = trustMarkService.getTrustMark(account, trustMarkRequest)

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/trust-mark+jwt"),
                body = result.value
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

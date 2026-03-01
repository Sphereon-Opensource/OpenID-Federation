package com.sphereon.openid.fed.server.federation.api.http.command

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
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.openid.fed.services.TrustMarkService
import com.sphereon.crypto.core.jose.JwtPayload
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwsUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== Trust Mark Status GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkStatusRootEndpointCommand::class)
class GetTrustMarkStatusRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkStatusRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkStatusRootEndpointCommand.ENDPOINT
), GetTrustMarkStatusRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkJwt = request.queryParameters["trust_mark"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark' parameter"))

        val payload = decodeTrustMarkJwtPayload(trustMarkJwt)
            ?: return Ok(errorResponse(400, "Invalid trust_mark JWT: cannot decode claims"))

        val statusRequest = TrustMarkStatusRequest(
            sub = payload.sub ?: return Ok(errorResponse(400, "Trust mark JWT missing 'sub' claim")),
            trustMarkType = payload.getString("trust_mark_type")
                ?: return Ok(errorResponse(400, "Trust mark JWT missing 'trust_mark_type' claim"))
        )

        return handleTrustMarkStatusResponse(tenantId, statusRequest, trustMarkService, json)
    }
}

// ==================== Trust Mark Status POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkStatusRootEndpointCommand::class)
class TrustMarkStatusRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkStatusRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkStatusRootEndpointCommand.ENDPOINT
), TrustMarkStatusRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkJwt = params["trust_mark"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark' parameter"))

        val payload = decodeTrustMarkJwtPayload(trustMarkJwt)
            ?: return Ok(errorResponse(400, "Invalid trust_mark JWT: cannot decode claims"))

        val statusRequest = TrustMarkStatusRequest(
            sub = payload.sub ?: return Ok(errorResponse(400, "Trust mark JWT missing 'sub' claim")),
            trustMarkType = payload.getString("trust_mark_type")
                ?: return Ok(errorResponse(400, "Trust mark JWT missing 'trust_mark_type' claim"))
        )

        return handleTrustMarkStatusResponse(tenantId, statusRequest, trustMarkService, json)
    }
}

// ==================== Trust Mark Status GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkStatusAccountEndpointCommand::class)
class GetTrustMarkStatusAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkStatusAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkStatusAccountEndpointCommand.ENDPOINT
), GetTrustMarkStatusAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkJwt = request.queryParameters["trust_mark"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark' parameter"))

        val payload = decodeTrustMarkJwtPayload(trustMarkJwt)
            ?: return Ok(errorResponse(400, "Invalid trust_mark JWT: cannot decode claims"))

        val statusRequest = TrustMarkStatusRequest(
            sub = payload.sub ?: return Ok(errorResponse(400, "Trust mark JWT missing 'sub' claim")),
            trustMarkType = payload.getString("trust_mark_type")
                ?: return Ok(errorResponse(400, "Trust mark JWT missing 'trust_mark_type' claim"))
        )

        return handleTrustMarkStatusResponse(tenantId, statusRequest, trustMarkService, json)
    }
}

// ==================== Trust Mark Status POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkStatusAccountEndpointCommand::class)
class TrustMarkStatusAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkStatusAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkStatusAccountEndpointCommand.ENDPOINT
), TrustMarkStatusAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkJwt = params["trust_mark"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark' parameter"))

        val payload = decodeTrustMarkJwtPayload(trustMarkJwt)
            ?: return Ok(errorResponse(400, "Invalid trust_mark JWT: cannot decode claims"))

        val statusRequest = TrustMarkStatusRequest(
            sub = payload.sub ?: return Ok(errorResponse(400, "Trust mark JWT missing 'sub' claim")),
            trustMarkType = payload.getString("trust_mark_type")
                ?: return Ok(errorResponse(400, "Trust mark JWT missing 'trust_mark_type' claim"))
        )

        return handleTrustMarkStatusResponse(tenantId, statusRequest, trustMarkService, json)
    }
}

// ==================== Trust Mark Status Response Helper ====================

private suspend fun handleTrustMarkStatusResponse(
    tenantId: String,
    statusRequest: TrustMarkStatusRequest,
    trustMarkService: TrustMarkService,
    json: Json
): IdkResult<GenericHttpResponse, IdkError> {
    val result = trustMarkService.getSignedTrustMarkStatusJwt(tenantId, statusRequest)

    return if (result.isOk) {
        Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/trust-mark-status-response+jwt"),
            body = result.value
        ))
    } else {
        val error = result.error
        Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
    }
}

// ==================== Trust Mark List GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkListRootEndpointCommand::class)
class TrustMarkListRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkListRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkListRootEndpointCommand.ENDPOINT
), TrustMarkListRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostTrustMarkListRootEndpointCommand::class)
class PostTrustMarkListRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PostTrustMarkListRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostTrustMarkListRootEndpointCommand.ENDPOINT
), PostTrustMarkListRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkListAccountEndpointCommand::class)
class TrustMarkListAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = TrustMarkListAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = TrustMarkListAccountEndpointCommand.ENDPOINT
), TrustMarkListAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostTrustMarkListAccountEndpointCommand::class)
class PostTrustMarkListAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = PostTrustMarkListAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostTrustMarkListAccountEndpointCommand.ENDPOINT
), PostTrustMarkListAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Get Trust Mark GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkRootEndpointCommand::class)
class GetTrustMarkRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkRootEndpointCommand.ENDPOINT
), GetTrustMarkRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostGetTrustMarkRootEndpointCommand::class)
class PostGetTrustMarkRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = PostGetTrustMarkRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostGetTrustMarkRootEndpointCommand.ENDPOINT
), PostGetTrustMarkRootEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkAccountEndpointCommand::class)
class GetTrustMarkAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = GetTrustMarkAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetTrustMarkAccountEndpointCommand.ENDPOINT
), GetTrustMarkAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostGetTrustMarkAccountEndpointCommand::class)
class PostGetTrustMarkAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService
) : HttpEndpointCommandAdapter(
    id = PostGetTrustMarkAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostGetTrustMarkAccountEndpointCommand.ENDPOINT
), PostGetTrustMarkAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(errorResponse(400, "Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Shared Helpers ====================

private suspend fun handleTrustMarkList(
    tenantId: String,
    trustMarkId: String,
    sub: String?,
    trustMarkService: TrustMarkService,
    json: Json
): IdkResult<GenericHttpResponse, IdkError> {
    val listRequest = TrustMarkListRequest(
        trustMarkType = trustMarkId,
        sub = sub
    )

    val result = trustMarkService.getTrustMarkedSubs(tenantId, listRequest)

    return if (result.isOk) {
        Ok(jsonResponse(200, json.encodeToString(result.value)))
    } else {
        val error = result.error
        Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
    }
}

private suspend fun handleGetTrustMark(
    tenantId: String,
    trustMarkId: String,
    sub: String,
    trustMarkService: TrustMarkService
): IdkResult<GenericHttpResponse, IdkError> {
    val trustMarkRequest = TrustMarkRequest(
        trustMarkType = trustMarkId,
        sub = sub
    )

    val result = trustMarkService.getTrustMark(tenantId, trustMarkRequest)

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

private fun decodeTrustMarkJwtPayload(trustMarkJwt: String): JwtPayload? {
    return try {
        val general = JwsUtils.compactToGeneral(JwsCompact(trustMarkJwt))
        val payloadJson = JwsUtils.decodeBase64UrlToJson(general.payload)
        JwtPayload(payloadJson)
    } catch (_: Exception) {
        null
    }
}

private fun parseTrustMarkFormParams(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").associate { param ->
        val parts = param.split("=", limit = 2)
        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
        val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
        key to value
    }
}

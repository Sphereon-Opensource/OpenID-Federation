package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.server.federation.api.http.auth.FederationEndpointClientAuthService
import com.sphereon.openid.fed.server.federation.api.http.auth.asAuthParams
import com.sphereon.openid.fed.server.federation.api.http.auth.enforceFederationClientAuth
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.core.api.http.response.jsonResponse
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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Trust Mark Status GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetTrustMarkStatusRootEndpointCommand>())
class GetTrustMarkStatusRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK_STATUS, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val status = parseTrustMarkStatusParams(request.queryParameters.mapValues { it.value ?: "" })
            ?: return Ok(
                FederationErrorResponses.invalidRequest(
                    "Provide trust_mark (JWT), or sub + trust_mark_type (alias i) [+ optional iat]"
                )
            )

        return handleTrustMarkStatusResponse(
            tenantId, status.request, trustMarkService, status.trustMarkJwt
        )
    }
}

// ==================== Trust Mark Status POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustMarkStatusRootEndpointCommand>())
class TrustMarkStatusRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK_STATUS, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)
        val status = parseTrustMarkStatusParams(params)
            ?: return Ok(
                FederationErrorResponses.invalidRequest(
                    "Provide trust_mark (JWT), or sub + trust_mark_type (alias i) [+ optional iat]"
                )
            )

        return handleTrustMarkStatusResponse(
            tenantId, status.request, trustMarkService, status.trustMarkJwt
        )
    }
}

// ==================== Trust Mark Status GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetTrustMarkStatusAccountEndpointCommand>())
class GetTrustMarkStatusAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK_STATUS, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val status = parseTrustMarkStatusParams(request.queryParameters.mapValues { it.value ?: "" })
            ?: return Ok(
                FederationErrorResponses.invalidRequest(
                    "Provide trust_mark (JWT), or sub + trust_mark_type (alias i) [+ optional iat]"
                )
            )

        return handleTrustMarkStatusResponse(
            tenantId, status.request, trustMarkService, status.trustMarkJwt
        )
    }
}

// ==================== Trust Mark Status POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustMarkStatusAccountEndpointCommand>())
class TrustMarkStatusAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK_STATUS, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)
        val status = parseTrustMarkStatusParams(params)
            ?: return Ok(
                FederationErrorResponses.invalidRequest(
                    "Provide trust_mark (JWT), or sub + trust_mark_type (alias i) [+ optional iat]"
                )
            )

        return handleTrustMarkStatusResponse(
            tenantId, status.request, trustMarkService, status.trustMarkJwt
        )
    }
}

// ==================== Trust Mark Status Response Helper ====================

/**
 * OIDFed 1.1 §8.4.1: either `trust_mark` (JWT) **or** discrete `sub` + type (`i` / `trust_mark_type`) + optional `iat`.
 */
private data class ParsedTrustMarkStatus(
    val request: TrustMarkStatusRequest,
    val trustMarkJwt: String?,
)

private fun parseTrustMarkStatusParams(params: Map<String, String>): ParsedTrustMarkStatus? {
    val trustMarkJwt = params["trust_mark"]?.takeIf { it.isNotBlank() }
    if (trustMarkJwt != null) {
        val payload = decodeTrustMarkJwtPayload(trustMarkJwt) ?: return null
        val sub = payload.sub?.takeIf { it.isNotBlank() } ?: return null
        val type = payload.getString("trust_mark_type")?.takeIf { it.isNotBlank() } ?: return null
        return ParsedTrustMarkStatus(
            request = TrustMarkStatusRequest(
                sub = sub,
                trustMarkType = type,
                iat = readIatClaim(payload),
            ),
            trustMarkJwt = trustMarkJwt,
        )
    }

    val sub = params["sub"]?.takeIf { it.isNotBlank() } ?: return null
    // Spec short name `i` = Trust Mark type identifier; also accept trust_mark_type
    val type = params["i"]?.takeIf { it.isNotBlank() }
        ?: params["trust_mark_type"]?.takeIf { it.isNotBlank() }
        ?: return null
    val iat = params["iat"]?.toDoubleOrNull()
    return ParsedTrustMarkStatus(
        request = TrustMarkStatusRequest(sub = sub, trustMarkType = type, iat = iat),
        trustMarkJwt = null,
    )
}

private suspend fun handleTrustMarkStatusResponse(
    tenantId: String,
    statusRequest: TrustMarkStatusRequest,
    trustMarkService: TrustMarkService,
    trustMarkJwt: String?,
): IdkResult<GenericHttpResponse, IdkError> {
    // OIDFed 1.1 §8.4.1: primary input is the Trust Mark JWT when provided
    val result = trustMarkService.getSignedTrustMarkStatusJwt(tenantId, statusRequest, trustMarkJwt)

    return if (result.isOk) {
        Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/trust-mark-status-response+jwt"),
            body = result.value
        ))
    } else {
        val error = result.error
        Ok(FederationErrorResponses.fromServiceError(error))
    }
}

private fun readIatClaim(payload: JwtPayload): Double? =
    try {
        payload.getString("iat")?.toDoubleOrNull()
            ?: payload.getPrimitive("iat")?.content?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }

// ==================== Trust Mark List GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustMarkListRootEndpointCommand>())
class TrustMarkListRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK_LIST, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostTrustMarkListRootEndpointCommand>())
class PostTrustMarkListRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK_LIST, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustMarkListAccountEndpointCommand>())
class TrustMarkListAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK_LIST, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Trust Mark List POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostTrustMarkListAccountEndpointCommand>())
class PostTrustMarkListAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val json: Json,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK_LIST, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]

        return handleTrustMarkList(tenantId, trustMarkId, sub, trustMarkService, json)
    }
}

// ==================== Get Trust Mark GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetTrustMarkRootEndpointCommand>())
class GetTrustMarkRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostGetTrustMarkRootEndpointCommand>())
class PostGetTrustMarkRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val clientAuth: FederationEndpointClientAuthService,
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

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.TRUST_MARK, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val tenantId = tenantContextResolver.resolveTenantIdByName(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetTrustMarkAccountEndpointCommand>())
class GetTrustMarkAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val trustMarkId = request.queryParameters["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))

        return handleGetTrustMark(tenantId, trustMarkId, sub, trustMarkService)
    }
}

// ==================== Get Trust Mark POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostGetTrustMarkAccountEndpointCommand>())
class PostGetTrustMarkAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val trustMarkService: TrustMarkService,
    private val clientAuth: FederationEndpointClientAuthService,
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
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.TRUST_MARK, methodIsPost = true,
            params = request.body?.let { parseTrustMarkFormParams(it) } ?: emptyMap(),
        )?.let { return Ok(it) }

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseTrustMarkFormParams(body)

        val trustMarkId = params["trust_mark_type"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'trust_mark_type' parameter"))
        val sub = params["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))

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
        Ok(FederationErrorResponses.fromServiceError(error))
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
        Ok(FederationErrorResponses.fromServiceError(error))
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

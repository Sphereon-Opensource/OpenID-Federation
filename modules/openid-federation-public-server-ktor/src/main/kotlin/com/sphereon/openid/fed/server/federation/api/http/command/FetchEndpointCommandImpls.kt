package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.config.FederationEndpointKind
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.command.subordinate.FetchSubordinateStatementCommand
import com.sphereon.openid.fed.services.command.subordinate.FetchSubordinateStatementArgs
import com.sphereon.openid.fed.server.federation.api.http.FederationErrorResponses
import com.sphereon.openid.fed.server.federation.api.http.auth.FederationEndpointClientAuthService
import com.sphereon.openid.fed.server.federation.api.http.auth.asAuthParams
import com.sphereon.openid.fed.server.federation.api.http.auth.enforceFederationClientAuth
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== Fetch Subordinate Statement GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FetchSubordinateRootEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(FetchSubordinateRootEndpointCommand.COMMAND_ID)
class FetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val clientAuth: FederationEndpointClientAuthService,
    private val fetchStatement: FetchSubordinateStatementCommand,
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateRootEndpointCommand.ENDPOINT
), FetchSubordinateRootEndpointCommand {


    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.FETCH, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val issParam = request.queryParameters["iss"]

        return fetchSubordinateStatement(tenantContextResolver, fetchStatement, Constants.DEFAULT_ROOT_USERNAME, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostFetchSubordinateRootEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(PostFetchSubordinateRootEndpointCommand.COMMAND_ID)
class PostFetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val clientAuth: FederationEndpointClientAuthService,
    private val fetchStatement: FetchSubordinateStatementCommand,
) : HttpEndpointCommandAdapter(
    id = PostFetchSubordinateRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostFetchSubordinateRootEndpointCommand.ENDPOINT
), PostFetchSubordinateRootEndpointCommand {


    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseFormParams(body)

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, Constants.DEFAULT_ROOT_USERNAME,
            FederationEndpointKind.FETCH, methodIsPost = true,
            params = params,
        )?.let { return Ok(it) }

        val sub = params["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val issParam = params["iss"]

        return fetchSubordinateStatement(tenantContextResolver, fetchStatement, Constants.DEFAULT_ROOT_USERNAME, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FetchSubordinateAccountEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(FetchSubordinateAccountEndpointCommand.COMMAND_ID)
class FetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val clientAuth: FederationEndpointClientAuthService,
    private val fetchStatement: FetchSubordinateStatementCommand,
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateAccountEndpointCommand.ENDPOINT
), FetchSubordinateAccountEndpointCommand {


    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.FETCH, methodIsPost = false,
            params = request.queryParameters.asAuthParams(),
        )?.let { return Ok(it) }

        val sub = request.queryParameters["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val issParam = request.queryParameters["iss"]

        return fetchSubordinateStatement(tenantContextResolver, fetchStatement, username, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PostFetchSubordinateAccountEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(PostFetchSubordinateAccountEndpointCommand.COMMAND_ID)
class PostFetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val clientAuth: FederationEndpointClientAuthService,
    private val fetchStatement: FetchSubordinateStatementCommand,
) : HttpEndpointCommandAdapter(
    id = PostFetchSubordinateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostFetchSubordinateAccountEndpointCommand.ENDPOINT
), PostFetchSubordinateAccountEndpointCommand {


    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Username parameter required"))

        val body = request.body ?: return Ok(FederationErrorResponses.invalidRequest("Request body is required"))
        val params = parseFormParams(body)

        enforceFederationClientAuth(
            clientAuth, tenantContextResolver, username,
            FederationEndpointKind.FETCH, methodIsPost = true,
            params = params,
        )?.let { return Ok(it) }

        val sub = params["sub"]
            ?: return Ok(FederationErrorResponses.invalidRequest("Missing 'sub' parameter"))
        val issParam = params["iss"]

        return fetchSubordinateStatement(tenantContextResolver, fetchStatement, username, sub, issParam)
    }
}

// ==================== Shared Helpers ====================

/**
 * Fetch a subordinate statement. If [issOverride] is provided (per 1.1 spec `iss` parameter),
 * it is used directly as the issuer identifier for the lookup. Otherwise, the issuer is
 * resolved from the tenant account.
 */
private suspend fun fetchSubordinateStatement(
    tenantContextResolver: TenantContextResolver,
    fetchStatement: FetchSubordinateStatementCommand,
    username: String,
    sub: String,
    issOverride: String? = null
): IdkResult<GenericHttpResponse, IdkError> {
    val iss = if (issOverride != null) {
        issOverride
    } else {
        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(FederationErrorResponses.notFound("Tenant not found"))
        tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(FederationErrorResponses.invalidIssuer("Tenant identifier not found"))
    }

    if (iss == sub) {
        return Ok(FederationErrorResponses.invalidRequest("The subject must differ from the issuing entity"))
    }

    val found = fetchStatement.execute(FetchSubordinateStatementArgs(iss, sub))
    if (found.isErr) return Ok(FederationErrorResponses.notFound("Subordinate statement not found"))
    val statement = found.value

    return Ok(GenericHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
        body = statement
    ))
}

private fun parseFormParams(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").associate { param ->
        val parts = param.split("=", limit = 2)
        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
        val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
        key to value
    }
}

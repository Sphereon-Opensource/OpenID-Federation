package com.sphereon.openid.fed.server.federation.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.errorResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.persistence.Persistence
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// ==================== Fetch Subordinate Statement GET (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateRootEndpointCommand::class)
class FetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateRootEndpointCommand.ENDPOINT
), FetchSubordinateRootEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val issParam = request.queryParameters["iss"]

        return fetchSubordinateStatement(tenantContextResolver, subordinateStatementQueries, Constants.DEFAULT_ROOT_USERNAME, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement POST (Root) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostFetchSubordinateRootEndpointCommand::class)
class PostFetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = PostFetchSubordinateRootEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostFetchSubordinateRootEndpointCommand.ENDPOINT
), PostFetchSubordinateRootEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseFormParams(body)

        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val issParam = params["iss"]

        return fetchSubordinateStatement(tenantContextResolver, subordinateStatementQueries, Constants.DEFAULT_ROOT_USERNAME, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement GET (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateAccountEndpointCommand::class)
class FetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = FetchSubordinateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = FetchSubordinateAccountEndpointCommand.ENDPOINT
), FetchSubordinateAccountEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val sub = request.queryParameters["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val issParam = request.queryParameters["iss"]

        return fetchSubordinateStatement(tenantContextResolver, subordinateStatementQueries, username, sub, issParam)
    }
}

// ==================== Fetch Subordinate Statement POST (Per Account) ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PostFetchSubordinateAccountEndpointCommand::class)
class PostFetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : HttpEndpointCommandAdapter(
    id = PostFetchSubordinateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = PostFetchSubordinateAccountEndpointCommand.ENDPOINT
), PostFetchSubordinateAccountEndpointCommand {

    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val requestWithParams = request.withExtractedParams(endpoint.pathPattern)
        val username = requestWithParams.pathParams["username"]
            ?: return Ok(errorResponse(400, "Username parameter required"))

        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))
        val params = parseFormParams(body)

        val sub = params["sub"]
            ?: return Ok(errorResponse(400, "Missing 'sub' parameter"))
        val issParam = params["iss"]

        return fetchSubordinateStatement(tenantContextResolver, subordinateStatementQueries, username, sub, issParam)
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
    subordinateStatementQueries: com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries,
    username: String,
    sub: String,
    issOverride: String? = null
): IdkResult<GenericHttpResponse, IdkError> {
    val iss = if (issOverride != null) {
        issOverride
    } else {
        val tenantId = tenantContextResolver.resolveTenantIdByName(username)
            ?: return Ok(errorResponse(404, "Tenant not found"))
        tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(errorResponse(404, "Tenant identifier not found"))
    }

    val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
        ?: return Ok(errorResponse(404, "Subordinate statement not found"))

    return Ok(GenericHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
        body = statement.statement
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

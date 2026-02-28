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
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Custom content type for entity statements (JWTs).
 */
private val EntityStatementJwtMediaType = MediaType.Custom("application/entity-statement+jwt")

// ==================== Fetch Subordinate Statement (Root) Endpoint ====================

interface FetchSubordinateRootEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/fetch",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchSubordinateStatement",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateRootEndpointCommand::class)
class FetchSubordinateRootEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val accountService: AccountService
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

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val issResult = accountService.getAccountIdentifierByAccount(account)
        if (issResult.isErr) {
            return Ok(errorResponse(issResult.error.httpStatusValue, issResult.error.message.defaultMessage))
        }
        val iss = issResult.value

        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Subordinate statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

// ==================== Fetch Subordinate Statement (Per Account) Endpoint ====================

interface FetchSubordinateAccountEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.fetch"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/fetch",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "fetchAccountSubordinateStatement",
            tags = setOf("federation"),
            summary = "Fetch subordinate entity statement for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateAccountEndpointCommand::class)
class FetchSubordinateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver,
    private val accountService: AccountService
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

        val account = accountResolver.resolveAccountByUsername(username)
            ?: return Ok(errorResponse(404, "Account not found"))

        val issResult = accountService.getAccountIdentifierByAccount(account)
        if (issResult.isErr) {
            return Ok(errorResponse(issResult.error.httpStatusValue, issResult.error.message.defaultMessage))
        }
        val iss = issResult.value

        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Subordinate statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

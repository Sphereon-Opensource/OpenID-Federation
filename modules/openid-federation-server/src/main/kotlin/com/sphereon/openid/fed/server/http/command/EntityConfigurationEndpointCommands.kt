package com.sphereon.openid.fed.server.http.command

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
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.persistence.Persistence
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Custom content type for entity statements (JWTs).
 */
private val EntityStatementJwtMediaType = MediaType.Custom("application/entity-statement+jwt")

// ==================== Entity Configuration (Root) Endpoint ====================

interface GetEntityConfigurationEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.entity-configuration"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/.well-known/openid-federation",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "getEntityConfiguration",
            tags = setOf("federation"),
            summary = "Get Entity Configuration Statement"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetEntityConfigurationEndpointCommand::class)
class GetEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver
) : HttpEndpointCommandAdapter(
    id = GetEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetEntityConfigurationEndpointCommand.ENDPOINT
), GetEntityConfigurationEndpointCommand {

    private val entityConfigStatementQueries = Persistence.entityConfigurationStatementQueries

    override suspend fun doExecute(
        args: GenericHttpRequest,
        sessionContext: SessionContext,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            ?: return Ok(errorResponse(404, "Account not found"))

        val statement = entityConfigStatementQueries
            .findLatestByAccountId(account.id)
            .executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

// ==================== Entity Configuration (Per Account) Endpoint ====================

interface GetAccountEntityConfigurationEndpointCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "fed.server.account.entity-configuration"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{username}/.well-known/openid-federation",
            produces = setOf(EntityStatementJwtMediaType),
            operationId = "getAccountEntityConfiguration",
            tags = setOf("federation"),
            summary = "Get Entity Configuration Statement for a specific account"
        )
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAccountEntityConfigurationEndpointCommand::class)
class GetAccountEntityConfigurationEndpointCommandImpl(
    execution: SessionExecution,
    private val accountResolver: FederationAccountResolver
) : HttpEndpointCommandAdapter(
    id = GetAccountEntityConfigurationEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetAccountEntityConfigurationEndpointCommand.ENDPOINT
), GetAccountEntityConfigurationEndpointCommand {

    private val entityConfigStatementQueries = Persistence.entityConfigurationStatementQueries

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

        val statement = entityConfigStatementQueries
            .findLatestByAccountId(account.id)
            .executeAsOneOrNull()
            ?: return Ok(errorResponse(404, "Entity Configuration Statement not found"))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/entity-statement+jwt"),
            body = statement.statement
        ))
    }
}

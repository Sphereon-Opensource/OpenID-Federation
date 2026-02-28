package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.errorResponse
import com.sphereon.core.api.http.jsonResponse
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.mappers.toAccountsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.di.session.SessionScope

// ==================== List Accounts Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListAccountsEndpointCommand::class)
class ListAccountsEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListAccountsEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListAccountsEndpointCommand.ENDPOINT
), ListAccountsEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val result = accountService.getAllAccounts()

        return if (result.isOk) {
            val accounts = result.value.toAccountsResponse()
            Ok(jsonResponse(200, json.encodeToString(accounts)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Account Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateAccountEndpointCommand::class)
class CreateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateAccountEndpointCommand.ENDPOINT
), CreateAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val body = request.body ?: return Ok(errorResponse(400, "Request body is required"))

        val createAccount = try {
            json.decodeFromString<CreateAccount>(body)
        } catch (e: Exception) {
            return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
        }

        val result = accountService.createAccount(createAccount)

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

// ==================== Delete Account Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteAccountEndpointCommand::class)
class DeleteAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val accountResolver: AccountResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = DeleteAccountEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteAccountEndpointCommand.ENDPOINT
), DeleteAccountEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val account = accountResolver.resolveAccount(request)
            ?: return Ok(errorResponse(404, "Account not found"))

        val result = accountService.deleteAccount(account)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

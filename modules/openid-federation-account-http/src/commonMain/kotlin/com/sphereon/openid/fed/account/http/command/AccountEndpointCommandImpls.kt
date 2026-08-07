package com.sphereon.openid.fed.account.http.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.account.AccountService
import com.sphereon.openid.fed.account.mappers.toAccountsResponse
import com.sphereon.openid.fed.account.error.AccountConstants
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.di.session.SessionScope

private fun platformAccountApiDisabled(configBinder: OidfConfigBinder): IdkResult<GenericHttpResponse, IdkError>? {
    if (configBinder.getIdentityConfig().isPlatform) {
        return Ok(
            errorResponse(
                410,
                "Account management APIs are disabled in PLATFORM identity mode. " +
                    "Use the host platform (IDK/EDK/VDX) for tenants, parties, and identities."
            )
        )
    }
    return null
}

// ==================== List Accounts Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListAccountsEndpointCommand>())
class ListAccountsEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val configBinder: OidfConfigBinder,
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
        platformAccountApiDisabled(configBinder)?.let { return it }
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
@ContributesBinding(SessionScope::class, binding = binding<CreateAccountEndpointCommand>())
class CreateAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val configBinder: OidfConfigBinder,
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
        platformAccountApiDisabled(configBinder)?.let { return it }
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
@ContributesBinding(SessionScope::class, binding = binding<DeleteAccountEndpointCommand>())
class DeleteAccountEndpointCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val configBinder: OidfConfigBinder,
    private val tenantContextResolver: TenantContextResolver,
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
        platformAccountApiDisabled(configBinder)?.let { return it }
        val request = applyDuring(args)

        val username = request.headers[AccountConstants.ACCOUNT_HEADER]
            ?: request.headers[AccountConstants.ACCOUNT_HEADER.lowercase()]
            ?: Constants.DEFAULT_ROOT_USERNAME

        val accountResult = accountService.getAccountByUsername(username)
        if (accountResult.isErr) {
            return Ok(errorResponse(404, "Account not found"))
        }
        val account = accountResult.value

        val result = accountService.deleteAccount(account)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

/**
 * Service interface for get all accounts operation.
 */
interface GetAllAccountsCommandService {
    suspend fun getAllAccounts(): IdkResult<List<Account>, FederationError>
}

/**
 * Command to retrieve all accounts.
 * Uses Unit as the argument type since no input is required.
 */
interface GetAllAccountsCommand : Command<Unit, List<Account>, FederationError>, GetAllAccountsCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.account.get-all"
    }
}

package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount

/**
 * Service interface for create account operations.
 */
interface CreateAccountCommandService {
    suspend fun createAccount(account: CreateAccount): IdkResult<Account, FederationError>
}

/**
 * Command to create a new account.
 */
interface CreateAccountCommand : Command<CreateAccount, Account, FederationError>, CreateAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.account.create"
    }
}

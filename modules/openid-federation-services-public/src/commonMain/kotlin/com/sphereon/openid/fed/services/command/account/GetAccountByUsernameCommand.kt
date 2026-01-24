package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

data class GetAccountByUsernameArgs(val username: String)

/**
 * Service interface for get account by username operation.
 */
interface GetAccountByUsernameCommandService {
    suspend fun getAccountByUsername(username: String): IdkResult<Account, FederationError>
}

/**
 * Command to retrieve an account by username.
 */
interface GetAccountByUsernameCommand : Command<GetAccountByUsernameArgs, Account, FederationError>, GetAccountByUsernameCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.account.get-by-username"
    }
}

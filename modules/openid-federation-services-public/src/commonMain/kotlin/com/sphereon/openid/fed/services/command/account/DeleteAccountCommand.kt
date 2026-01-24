package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

data class DeleteAccountArgs(val account: Account)

/**
 * Service interface for delete account operation.
 */
interface DeleteAccountCommandService {
    suspend fun deleteAccount(account: Account): IdkResult<Account, FederationError>
}

/**
 * Command to delete an account.
 */
interface DeleteAccountCommand : Command<DeleteAccountArgs, Account, FederationError>, DeleteAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.account.delete"
    }
}

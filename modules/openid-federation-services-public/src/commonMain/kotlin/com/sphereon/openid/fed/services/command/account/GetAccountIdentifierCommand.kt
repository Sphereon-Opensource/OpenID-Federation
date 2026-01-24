package com.sphereon.openid.fed.services.command.account

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

data class GetAccountIdentifierArgs(val account: Account)

/**
 * Service interface for get account identifier operation.
 */
interface GetAccountIdentifierCommandService {
    suspend fun getAccountIdentifierByAccount(account: Account): IdkResult<String, FederationError>
}

/**
 * Command to retrieve the identifier for an account.
 */
interface GetAccountIdentifierCommand : Command<GetAccountIdentifierArgs, String, FederationError>, GetAccountIdentifierCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.account.get-identifier"
    }
}

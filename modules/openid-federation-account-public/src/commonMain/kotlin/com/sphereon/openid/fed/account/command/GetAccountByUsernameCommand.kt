package com.sphereon.openid.fed.account.command

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

data class GetAccountByUsernameArgs(val username: String)

interface GetAccountByUsernameCommand : ServiceCommand<GetAccountByUsernameArgs, Account, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.account.get-by-username"
    }
}

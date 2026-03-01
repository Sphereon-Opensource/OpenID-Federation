package com.sphereon.openid.fed.account.command

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

data class GetAccountIdentifierArgs(val account: Account)

interface GetAccountIdentifierCommand : ServiceCommand<GetAccountIdentifierArgs, String> {
    companion object {
        const val COMMAND_ID = "fed.account.get-identifier"
    }
}

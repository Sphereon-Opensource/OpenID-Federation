package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest

data class GetTrustMarkedSubsArgs(val account: Account, val request: TrustMarkListRequest)

interface GetTrustMarkedSubsCommand : ServiceCommand<GetTrustMarkedSubsArgs, Array<String>> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-marked-subs"
    }
}

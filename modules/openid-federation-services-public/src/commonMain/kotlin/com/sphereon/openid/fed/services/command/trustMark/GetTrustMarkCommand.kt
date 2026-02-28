package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest

data class GetTrustMarkArgs(val account: Account, val request: TrustMarkRequest)

interface GetTrustMarkCommand : ServiceCommand<GetTrustMarkArgs, String> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get"
    }
}

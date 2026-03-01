package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest

data class GetTrustMarkStatusArgs(val tenantId: String, val request: TrustMarkStatusRequest)

interface GetTrustMarkStatusCommand : ServiceCommand<GetTrustMarkStatusArgs, Boolean> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-status"
    }
}

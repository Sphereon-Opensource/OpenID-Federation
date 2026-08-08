package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest

data class GetTrustMarkedSubsArgs(val tenantId: String, val request: TrustMarkListRequest)

interface GetTrustMarkedSubsCommand : ServiceCommand<GetTrustMarkedSubsArgs, Array<String>, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-marked-subs"
    }
}

package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TrustMarkRequest

data class GetTrustMarkArgs(val tenantId: String, val request: TrustMarkRequest)

interface GetTrustMarkCommand : ServiceCommand<GetTrustMarkArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get"
    }
}

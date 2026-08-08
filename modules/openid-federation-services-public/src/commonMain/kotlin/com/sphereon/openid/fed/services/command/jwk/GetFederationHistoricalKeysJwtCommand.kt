package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand


data class GetFederationHistoricalKeysJwtArgs(
    val tenantId: String
)

interface GetFederationHistoricalKeysJwtCommand : ServiceCommand<GetFederationHistoricalKeysJwtArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.get-federation-historical-keys-jwt"
    }
}

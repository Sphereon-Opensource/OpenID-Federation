package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.openapi.models.Account

data class GetFederationHistoricalKeysJwtArgs(
    val account: Account
)

interface GetFederationHistoricalKeysJwtCommand : ServiceCommand<GetFederationHistoricalKeysJwtArgs, String> {
    companion object {
        const val COMMAND_ID = "fed.jwk.get-federation-historical-keys-jwt"
    }
}

package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account

/**
 * Arguments for the GetFederationHistoricalKeysJwt command.
 */
data class GetFederationHistoricalKeysJwtArgs(
    val account: Account
)

/**
 * Service interface for get federation historical keys JWT operation.
 */
interface GetFederationHistoricalKeysJwtCommandService {
    /**
     * Generates and returns a JWT representing the historical federation keys.
     *
     * @param account The account for which the federation historical keys JWT is being generated.
     * @return IdkResult containing the signed JWT or an error.
     */
    suspend fun getFederationHistoricalKeysJwt(account: Account): IdkResult<String, FederationError>
}

/**
 * Command to generate a JWT representing the historical federation keys.
 */
interface GetFederationHistoricalKeysJwtCommand : Command<GetFederationHistoricalKeysJwtArgs, String, FederationError>, GetFederationHistoricalKeysJwtCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.jwk.get-federation-historical-keys-jwt"
    }
}

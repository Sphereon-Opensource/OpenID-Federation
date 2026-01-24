package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk

/**
 * Arguments for the RevokeKey command.
 */
data class RevokeKeyArgs(
    val account: Account,
    val keyId: String,
    val reason: String?
)

/**
 * Service interface for revoke key operation.
 */
interface RevokeKeyCommandService {
    /**
     * Revokes a specific key associated with the provided account.
     *
     * @param account The account associated with the key to be revoked.
     * @param keyId The unique identifier of the key to be revoked.
     * @param reason An optional reason for revoking the key.
     * @return IdkResult containing the revoked AccountJwk or an error.
     */
    suspend fun revokeKey(account: Account, keyId: String, reason: String?): IdkResult<AccountJwk, FederationError>
}

/**
 * Command to revoke a specific key associated with an account.
 */
interface RevokeKeyCommand : Command<RevokeKeyArgs, AccountJwk, FederationError>, RevokeKeyCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.jwk.revoke-key"
    }
}

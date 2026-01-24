package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk

/**
 * Arguments for the GetKeys command.
 */
data class GetKeysArgs(
    val account: Account,
    val includeRevoked: Boolean = false
)

/**
 * Service interface for get keys operation.
 */
interface GetKeysCommandService {
    /**
     * Retrieves the keys associated with a given account.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @return IdkResult containing an array of AccountJwk or an error.
     */
    suspend fun getKeys(account: Account, includeRevoked: Boolean = false): IdkResult<Array<AccountJwk>, FederationError>
}

/**
 * Command to retrieve keys associated with an account.
 */
interface GetKeysCommand : Command<GetKeysArgs, Array<AccountJwk>, FederationError>, GetKeysCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.jwk.get-keys"
    }
}

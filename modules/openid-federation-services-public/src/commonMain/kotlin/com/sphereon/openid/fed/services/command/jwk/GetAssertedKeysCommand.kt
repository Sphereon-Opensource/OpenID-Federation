package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk

/**
 * Arguments for the GetAssertedKeys command.
 */
data class GetAssertedKeysArgs(
    val account: Account,
    val includeRevoked: Boolean = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

/**
 * Service interface for get asserted keys operation.
 */
interface GetAssertedKeysCommandService {
    /**
     * Retrieves the keys associated with the given account or returns an error if no keys are found.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @param kmsKeyRef Optional KMS Key reference filter.
     * @param kid Optional kid filter.
     * @return IdkResult containing an array of AccountJwk or an error.
     */
    suspend fun getAssertedKeysForAccount(
        account: Account,
        includeRevoked: Boolean = false,
        kmsKeyRef: String? = null,
        kid: String? = null
    ): IdkResult<Array<AccountJwk>, FederationError>
}

/**
 * Command to retrieve keys for an account with assertion that keys exist.
 */
interface GetAssertedKeysCommand : Command<GetAssertedKeysArgs, Array<AccountJwk>, FederationError>, GetAssertedKeysCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.jwk.get-asserted-keys"
    }
}

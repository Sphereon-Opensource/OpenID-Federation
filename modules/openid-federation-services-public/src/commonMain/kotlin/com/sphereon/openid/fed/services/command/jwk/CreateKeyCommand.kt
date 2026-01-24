package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.services.CreateKeyArgs

/**
 * Arguments for the CreateKey command.
 */
data class CreateKeyCommandArgs(
    val account: Account,
    val opts: CreateKeyArgs = CreateKeyArgs()
)

/**
 * Service interface for create key operation.
 */
interface CreateKeyCommandService {
    /**
     * Creates a new JSON Web Key (JWK) for the specified account.
     *
     * @param account The account for which a new JWK is being created.
     * @param opts Options for key creation.
     * @return IdkResult containing the created AccountJwk or an error.
     */
    suspend fun createKey(account: Account, opts: CreateKeyArgs = CreateKeyArgs()): IdkResult<AccountJwk, FederationError>
}

/**
 * Command to create a new JSON Web Key (JWK) for an account.
 */
interface CreateKeyCommand : Command<CreateKeyCommandArgs, AccountJwk, FederationError>, CreateKeyCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.jwk.create-key"
    }
}

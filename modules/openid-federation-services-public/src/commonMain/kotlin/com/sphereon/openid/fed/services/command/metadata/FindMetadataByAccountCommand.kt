package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata

/**
 * Arguments for the FindMetadataByAccount command.
 */
data class FindMetadataByAccountArgs(
    val account: Account
)

/**
 * Service interface for find metadata by account operation.
 */
interface FindMetadataByAccountCommandService {
    /**
     * Finds and retrieves a list of Metadata associated with the provided account.
     *
     * @param account The account for which metadata is to be fetched.
     * @return IdkResult containing a list of Metadata or an error.
     */
    suspend fun findByAccount(account: Account): IdkResult<List<Metadata>, FederationError>
}

/**
 * Command to find all metadata entries for an account.
 */
interface FindMetadataByAccountCommand : Command<FindMetadataByAccountArgs, List<Metadata>, FederationError>, FindMetadataByAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata.find-by-account"
    }
}

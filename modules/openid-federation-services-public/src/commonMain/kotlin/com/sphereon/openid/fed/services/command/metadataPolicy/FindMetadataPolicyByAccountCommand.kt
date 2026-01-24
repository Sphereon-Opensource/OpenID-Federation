package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy

/**
 * Arguments for the FindMetadataPolicyByAccount command.
 */
data class FindMetadataPolicyByAccountArgs(
    val account: Account
)

/**
 * Service interface for find metadata policy by account operation.
 */
interface FindMetadataPolicyByAccountCommandService {
    /**
     * Finds and retrieves a list of MetadataPolicy associated with the provided account.
     *
     * @param account The account for which metadata policy is to be fetched.
     * @return IdkResult containing a list of MetadataPolicy or an error.
     */
    suspend fun findByAccount(account: Account): IdkResult<List<MetadataPolicy>, FederationError>
}

/**
 * Command to find all metadata policy entries for an account.
 */
interface FindMetadataPolicyByAccountCommand : Command<FindMetadataPolicyByAccountArgs, List<MetadataPolicy>, FederationError>, FindMetadataPolicyByAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata-policy.find-by-account"
    }
}

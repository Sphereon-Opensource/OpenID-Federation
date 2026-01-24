package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy

/**
 * Arguments for the DeleteMetadataPolicy command.
 */
data class DeleteMetadataPolicyArgs(
    val account: Account,
    val id: String
)

/**
 * Service interface for delete metadata policy operation.
 */
interface DeleteMetadataPolicyCommandService {
    /**
     * Deletes a metadata policy record associated with the given account and ID.
     *
     * @param account The account associated with the metadata policy to be deleted.
     * @param id The unique identifier of the metadata policy record to delete.
     * @return IdkResult containing the deleted MetadataPolicy or an error.
     */
    suspend fun deletePolicy(account: Account, id: String): IdkResult<MetadataPolicy, FederationError>
}

/**
 * Command to delete a metadata policy entry.
 */
interface DeleteMetadataPolicyCommand : Command<DeleteMetadataPolicyArgs, MetadataPolicy, FederationError>, DeleteMetadataPolicyCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata-policy.delete"
    }
}

package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata

/**
 * Arguments for the DeleteMetadata command.
 */
data class DeleteMetadataArgs(
    val account: Account,
    val id: String
)

/**
 * Service interface for delete metadata operation.
 */
interface DeleteMetadataCommandService {
    /**
     * Deletes a metadata record associated with the given account and ID.
     *
     * @param account The account associated with the metadata to be deleted.
     * @param id The unique identifier of the metadata record to delete.
     * @return IdkResult containing the deleted Metadata or an error.
     */
    suspend fun deleteMetadata(account: Account, id: String): IdkResult<Metadata, FederationError>
}

/**
 * Command to delete a metadata entry.
 */
interface DeleteMetadataCommand : Command<DeleteMetadataArgs, Metadata, FederationError>, DeleteMetadataCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata.delete"
    }
}

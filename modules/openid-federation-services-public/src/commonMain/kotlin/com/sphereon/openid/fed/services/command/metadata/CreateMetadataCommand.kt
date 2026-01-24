package com.sphereon.openid.fed.services.command.metadata

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata
import kotlinx.serialization.json.JsonElement

/**
 * Arguments for the CreateMetadata command.
 */
data class CreateMetadataArgs(
    val account: Account,
    val key: String,
    val metadata: JsonElement
)

/**
 * Service interface for create metadata operation.
 */
interface CreateMetadataCommandService {
    /**
     * Creates a new entity configuration metadata entry for a specified account and key.
     *
     * @param account The account for which the metadata is being created.
     * @param key The unique key representing the metadata.
     * @param metadata The metadata content to be associated with the account and key.
     * @return IdkResult containing the created Metadata or an error.
     */
    suspend fun createMetadata(account: Account, key: String, metadata: JsonElement): IdkResult<Metadata, FederationError>
}

/**
 * Command to create a new metadata entry for an account.
 */
interface CreateMetadataCommand : Command<CreateMetadataArgs, Metadata, FederationError>, CreateMetadataCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.metadata.create"
    }
}

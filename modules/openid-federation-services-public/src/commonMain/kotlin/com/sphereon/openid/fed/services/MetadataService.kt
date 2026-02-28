package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Metadata
import kotlinx.serialization.json.JsonElement

/**
 * Service interface for managing metadata configurations associated with an account.
 * The operations include creating, retrieving, and deleting metadata entries.
 *
 * This service aggregates all metadata-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface MetadataService {

    // Convenience methods that delegate to command services
    // These provide backward compatibility with existing code

    /**
     * Creates a new entity configuration metadata entry for a specified account and key.
     *
     * @param account The account for which the metadata is being created.
     * @param key The unique key representing the metadata.
     * @param metadata The metadata content to be associated with the account and key.
     * @return FederationResult containing the created Metadata or an error.
     */
    suspend fun createMetadata(account: Account, key: String, metadata: JsonElement): FederationResult<Metadata>

    /**
     * Finds and retrieves a list of Metadata associated with the provided account.
     *
     * @param account The account for which metadata is to be fetched.
     * @return FederationResult containing a list of Metadata or an error.
     */
    suspend fun findByAccount(account: Account): FederationResult<List<Metadata>>

    /**
     * Deletes a metadata record associated with the given account and ID.
     *
     * @param account The account associated with the metadata to be deleted.
     * @param id The unique identifier of the metadata record to delete.
     * @return FederationResult containing the deleted Metadata or an error.
     */
    suspend fun deleteMetadata(account: Account, id: String): FederationResult<Metadata>
}

package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import kotlinx.serialization.json.JsonElement

/**
 * Service interface for managing metadata policy configurations associated with an account.
 * The operations include creating, retrieving, and deleting metadata policy entries.
 *
 * This service aggregates all metadata policy-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface MetadataPolicyService {

    // Convenience methods that delegate to command services

    /**
     * Creates a new entity configuration metadata policy entry for a specified account and key.
     *
     * @param account The account for which the metadata policy is being created.
     * @param key The unique key representing the metadata policy.
     * @param policy The policy content to be associated with the account and key.
     * @return FederationResult containing the created MetadataPolicy or an error.
     */
    suspend fun createPolicy(account: Account, key: String, policy: JsonElement): FederationResult<MetadataPolicy>

    /**
     * Finds and retrieves a list of MetadataPolicy associated with the provided account.
     *
     * @param account The account for which metadata policy is to be fetched.
     * @return FederationResult containing a list of MetadataPolicy or an error.
     */
    suspend fun findByAccount(account: Account): FederationResult<List<MetadataPolicy>>

    /**
     * Deletes a metadata policy record associated with the given account and ID.
     *
     * @param account The account associated with the metadata policy to be deleted.
     * @param id The unique identifier of the metadata policy record to delete.
     * @return FederationResult containing the deleted MetadataPolicy or an error.
     */
    suspend fun deletePolicy(account: Account, id: String): FederationResult<MetadataPolicy>
}

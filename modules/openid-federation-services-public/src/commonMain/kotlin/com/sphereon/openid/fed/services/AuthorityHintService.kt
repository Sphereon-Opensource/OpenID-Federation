package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.AuthorityHint

/**
 * Service interface responsible for managing operations related to AuthorityHint entities.
 * Provides functionality for creating, deleting, and retrieving AuthorityHint records
 * associated with an Account.
 *
 * This service aggregates all authority hint-related commands and provides both
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface AuthorityHintService {

    // Convenience methods that delegate to command services

    /**
     * Creates a new authority hint for the given account and identifier.
     *
     * @param account The account for which the authority hint is to be created.
     * @param identifier The unique identifier for the authority hint to be created.
     * @return FederationResult containing the created AuthorityHint or an error.
     */
    suspend fun createAuthorityHint(tenantId: String, identifier: String): FederationResult<AuthorityHint>

    /**
     * Deletes an AuthorityHint associated with the specified account and ID.
     *
     * @param account The account associated with the AuthorityHint to be deleted.
     * @param id The unique identifier of the AuthorityHint to delete.
     * @return FederationResult containing the deleted AuthorityHint or an error.
     */
    suspend fun deleteAuthorityHint(tenantId: String, id: String): FederationResult<AuthorityHint>

    /**
     * Finds authority hints associated with the specified account.
     *
     * @param account The account for which authority hints need to be retrieved.
     * @return FederationResult containing a list of authority hints or an error.
     */
    suspend fun findByAccount(tenantId: String): FederationResult<List<AuthorityHint>>
}

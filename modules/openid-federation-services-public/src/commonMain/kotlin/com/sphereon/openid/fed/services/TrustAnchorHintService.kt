package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint

/**
 * Service interface responsible for managing operations related to TrustAnchorHint entities.
 * Provides functionality for creating, deleting, and retrieving TrustAnchorHint records
 * associated with an Account.
 *
 * This service aggregates all trust anchor hint-related commands and provides both
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface TrustAnchorHintService {

    /**
     * Creates a new trust anchor hint for the given account and identifier.
     *
     * @param tenantId The tenant ID for which the trust anchor hint is to be created.
     * @param identifier The unique identifier for the trust anchor hint to be created.
     * @return FederationResult containing the created TrustAnchorHint or an error.
     */
    suspend fun createTrustAnchorHint(tenantId: String, identifier: String): FederationResult<TrustAnchorHint>

    /**
     * Deletes a TrustAnchorHint associated with the specified account and ID.
     *
     * @param tenantId The tenant ID associated with the TrustAnchorHint to be deleted.
     * @param id The unique identifier of the TrustAnchorHint to delete.
     * @return FederationResult containing the deleted TrustAnchorHint or an error.
     */
    suspend fun deleteTrustAnchorHint(tenantId: String, id: String): FederationResult<TrustAnchorHint>

    /**
     * Finds trust anchor hints associated with the specified account.
     *
     * @param tenantId The tenant ID for which trust anchor hints need to be retrieved.
     * @return FederationResult containing a list of trust anchor hints or an error.
     */
    suspend fun findByAccount(tenantId: String): FederationResult<List<TrustAnchorHint>>
}

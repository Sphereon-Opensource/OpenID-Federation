package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Service interface responsible for managing critical claims.
 * This interface provides functionalities to create, delete, and retrieve critical claims
 * associated with a specific account.
 *
 * This service aggregates all critical claim-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface CriticalClaimService {

    // Convenience methods that delegate to command services

    /**
     * Creates a critical claim for the given account and claim string.
     *
     * @param account The account for which the critical claim will be created.
     * @param claim The claim string that uniquely identifies the critical claim.
     * @return FederationResult containing the created CritEntity or an error.
     */
    suspend fun create(tenantId: String, claim: String): FederationResult<CritEntity>

    /**
     * Deletes a critical claim associated with a given account and claim ID.
     *
     * @param account The account from which the critical claim will be deleted.
     * @param id The unique identifier of the critical claim to be deleted.
     * @return FederationResult containing the deleted CritEntity or an error.
     */
    suspend fun delete(tenantId: String, id: String): FederationResult<CritEntity>

    /**
     * Retrieves all critical claims associated with the provided account.
     *
     * @param account The account for which critical claims are to be retrieved.
     * @return FederationResult containing an array of CritEntity or an error.
     */
    suspend fun findByAccount(tenantId: String): FederationResult<Array<CritEntity>>
}

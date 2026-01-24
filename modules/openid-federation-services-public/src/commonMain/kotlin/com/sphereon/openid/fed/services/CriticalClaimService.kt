package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.CreateCriticalClaimCommandService
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimCommand
import com.sphereon.openid.fed.services.command.criticalClaim.DeleteCriticalClaimCommandService
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountCommand
import com.sphereon.openid.fed.services.command.criticalClaim.FindCriticalClaimsByAccountCommandService

/**
 * Service interface responsible for managing critical claims.
 * This interface provides functionalities to create, delete, and retrieve critical claims
 * associated with a specific account.
 *
 * This service aggregates all critical claim-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface CriticalClaimService :
    CreateCriticalClaimCommandService,
    DeleteCriticalClaimCommandService,
    FindCriticalClaimsByAccountCommandService {

    /**
     * Provides access to individual critical claim commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all critical claim-related commands.
     */
    interface Commands {
        val create: CreateCriticalClaimCommand
        val delete: DeleteCriticalClaimCommand
        val findByAccount: FindCriticalClaimsByAccountCommand
    }

    // Convenience methods that delegate to command services

    /**
     * Creates a critical claim for the given account and claim string.
     *
     * @param account The account for which the critical claim will be created.
     * @param claim The claim string that uniquely identifies the critical claim.
     * @return FederationResult containing the created CritEntity or an error.
     */
    override suspend fun create(account: Account, claim: String): FederationResult<CritEntity>

    /**
     * Deletes a critical claim associated with a given account and claim ID.
     *
     * @param account The account from which the critical claim will be deleted.
     * @param id The unique identifier of the critical claim to be deleted.
     * @return FederationResult containing the deleted CritEntity or an error.
     */
    override suspend fun delete(account: Account, id: String): FederationResult<CritEntity>

    /**
     * Retrieves all critical claims associated with the provided account.
     *
     * @param account The account for which critical claims are to be retrieved.
     * @return FederationResult containing an array of CritEntity or an error.
     */
    override suspend fun findByAccount(account: Account): FederationResult<Array<CritEntity>>
}

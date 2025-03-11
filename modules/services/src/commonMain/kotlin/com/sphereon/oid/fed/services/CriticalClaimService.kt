package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Crit as CritEntity

/**
 * Service class responsible for managing critical claims.
 * This class provides functionalities to create, delete, and retrieve critical claims
 * associated with a specific account.
 */
class CriticalClaimService {
    /**
     * Logger instance specifically tagged for the `CriticalClaimService` class.
     */
    private val logger = Logger.tag("CriticalClaimService")

    /**
     * Creates a critical claim for the given account and claim string.
     *
     * This method ensures that a critical claim does not already exist for the specified account
     * and claim string before creating a new entry.
     *
     * @param account The account for which the critical claim will be created.
     * @param claim The claim string that uniquely identifies the critical claim.
     * @return The created critical claim represented as an instance of CritEntity.
     * @throws EntityAlreadyExistsException If a critical claim already exists for the given account and claim.
     * @throws IllegalStateException If the creation of the critical claim fails.
     */
    fun create(account: Account, claim: String): CritEntity {
        logger.info("Creating critical claim for account: ${account.username}, claim: $claim")
        try {
            logAccountId(account)
            logger.debug("Checking if critical claim already exists for claim: $claim")
            val existingCriticalClaim = Persistence.critQueries
                .findByAccountIdAndClaim(account.id, claim)
                .executeAsOneOrNull()
            if (existingCriticalClaim != null) {
                logger.warn("Critical claim already exists for claim: $claim")
                throw EntityAlreadyExistsException(Constants.CRIT_ALREADY_EXISTS)
            }
            val createdCriticalClaim = Persistence.critQueries
                .create(account.id, claim)
                .executeAsOneOrNull() ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_CRIT)
            logger.info("Successfully created critical claim with ID: ${createdCriticalClaim.id}")
            return createdCriticalClaim
        } catch (e: Exception) {
            logger.error("Failed to create critical claim for account: ${account.username}, claim: $claim", e)
            throw e
        }
    }

    /**
     * Deletes a critical claim associated with a given account and claim ID.
     *
     * @param account The account from which the critical claim will be deleted.
     * @param id The unique identifier of the critical claim to be deleted.
     * @return The deleted critical claim as a CritEntity.
     * @throws Exception If the operation fails to delete the critical claim.
     */
    fun delete(account: Account, id: Int): CritEntity {
        logger.info("Deleting critical claim ID: $id for account: ${account.username}")
        try {
            logAccountId(account)
            val deletedCriticalClaim = Persistence.critQueries
                .deleteByAccountIdAndId(account.id, id)
                .executeAsOneOrNull() ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_CRIT)
            logger.info("Successfully deleted critical claim with ID: $id")
            return deletedCriticalClaim
        } catch (e: Exception) {
            logger.error("Failed to delete critical claim ID: $id for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Retrieves critical claims associated with a specific account using the provided account ID.
     *
     * @param accountId The unique identifier for the account whose critical claims are to be fetched.
     * @return An array of critical claims (`CritEntity`) associated with the given account.
     */
    private fun getCriticalClaimsByAccountId(accountId: Int): Array<CritEntity> {
        logger.debug("Finding critical claims for account ID: $accountId")
        val criticalClaims = Persistence.critQueries.findByAccountId(accountId).executeAsList().toTypedArray()
        logger.debug("Found ${criticalClaims.size} critical claims for account ID: $accountId")
        return criticalClaims
    }

    /**
     * Retrieves all critical claims associated with the provided account.
     *
     * @param account The account for which critical claims are to be retrieved.
     * @return An array of CritEntity objects representing the critical claims for the given account.
     * @throws Exception if an error occurs while retrieving critical claims.
     */
    fun findByAccount(account: Account): Array<CritEntity> {
        logger.info("Finding critical claims for account: ${account.username}")
        try {
            logAccountId(account)
            val criticalClaims = getCriticalClaimsByAccountId(account.id)
            logger.info("Found ${criticalClaims.size} critical claims for account: ${account.username}")
            return criticalClaims
        } catch (e: Exception) {
            logger.error("Failed to find critical claims for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Logs the account ID for debugging purposes.
     *
     * @param account The account whose ID is to be logged.
     */
    private fun logAccountId(account: Account) {
        logger.debug("Using account with ID: ${account.id}")
    }
}
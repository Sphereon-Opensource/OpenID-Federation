package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Crit

class CritService {
    private val logger = Logger.tag("CritService")

    fun create(accountUsername: String, claim: String): Crit {
        logger.info("Creating crit for account: $accountUsername, claim: $claim")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            logger.debug("Checking if crit already exists for claim: $claim")
            val critAlreadyExists =
                Persistence.critQueries.findByAccountIdAndClaim(account.id, claim).executeAsOneOrNull()

            if (critAlreadyExists != null) {
                logger.warn("Crit already exists for claim: $claim")
                throw EntityAlreadyExistsException(Constants.CRIT_ALREADY_EXISTS)
            }

            val createdCrit = Persistence.critQueries.create(account.id, claim).executeAsOneOrNull()
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_CRIT)
            logger.info("Successfully created crit with ID: ${createdCrit.id}")

            return createdCrit
        } catch (e: Exception) {
            logger.error("Failed to create crit for account: $accountUsername, claim: $claim", e)
            throw e
        }
    }

    fun delete(accountUsername: String, id: Int): Crit {
        logger.info("Deleting crit ID: $id for account: $accountUsername")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val deletedCrit = Persistence.critQueries.deleteByAccountIdAndId(account.id, id).executeAsOneOrNull()
                ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_CRIT)
            logger.info("Successfully deleted crit with ID: $id")

            return deletedCrit
        } catch (e: Exception) {
            logger.error("Failed to delete crit ID: $id for account: $accountUsername", e)
            throw e
        }
    }

    private fun findByAccountId(accountId: Int): Array<Crit> {
        logger.debug("Finding crits for account ID: $accountId")
        val crits = Persistence.critQueries.findByAccountId(accountId).executeAsList().toTypedArray()
        logger.debug("Found ${crits.size} crits for account ID: $accountId")
        return crits
    }

    fun findByAccountUsername(accountUsername: String): Array<Crit> {
        logger.info("Finding crits for account: $accountUsername")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val crits = findByAccountId(account.id)
            logger.info("Found ${crits.size} crits for account: $accountUsername")
            return crits
        } catch (e: Exception) {
            logger.error("Failed to find crits for account: $accountUsername", e)
            throw e
        }
    }
}

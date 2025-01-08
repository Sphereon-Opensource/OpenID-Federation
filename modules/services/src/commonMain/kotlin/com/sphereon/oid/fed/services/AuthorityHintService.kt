package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.AuthorityHint

class AuthorityHintService {
    private val logger = Logger.tag("AuthorityHintService")

    fun createAuthorityHint(accountUsername: String, identifier: String): AuthorityHint {
        logger.debug("Attempting to create authority hint for account: $accountUsername with identifier: $identifier")

        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername", it)
            }

        val authorityHintAlreadyExists =
            Persistence.authorityHintQueries.findByAccountIdAndIdentifier(account.id, identifier).executeAsOneOrNull()

        if (authorityHintAlreadyExists != null) {
            throw EntityAlreadyExistsException(Constants.AUTHORITY_HINT_ALREADY_EXISTS).also {
                logger.error("Authority hint already exists for account: $accountUsername, identifier: $identifier", it)
            }
        }

        return try {
            Persistence.authorityHintQueries.create(account.id, identifier)
                .executeAsOneOrNull()
                ?.also { logger.info("Successfully created authority hint for account: $accountUsername with identifier: $identifier") }
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error(
                "Failed to create authority hint for account: $accountUsername with identifier: $identifier",
                e
            )
            throw e
        }
    }

    fun deleteAuthorityHint(accountUsername: String, id: Int): AuthorityHint {
        logger.debug("Attempting to delete authority hint with id: $id for account: $accountUsername")

        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername", it)
            }

        Persistence.authorityHintQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.AUTHORITY_HINT_NOT_FOUND).also {
                logger.error("Authority hint not found with id: $id for account: $accountUsername", it)
            }

        return try {
            Persistence.authorityHintQueries.delete(id).executeAsOneOrNull()
                ?.also { logger.info("Successfully deleted authority hint with id: $id for account: $accountUsername") }
                ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error("Failed to delete authority hint with id: $id for account: $accountUsername", e)
            throw e
        }
    }

    private fun findByAccountId(accountId: Int): Array<AuthorityHint> {
        logger.debug("Finding authority hints for account id: $accountId")
        return Persistence.authorityHintQueries.findByAccountId(accountId).executeAsList().toTypedArray()
            .also { logger.debug("Found ${it.size} authority hints for account id: $accountId") }
    }

    fun findByAccountUsername(accountUsername: String): Array<AuthorityHint> {
        logger.debug("Finding authority hints for account username: $accountUsername")

        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername", it)
            }

        return findByAccountId(account.id)
            .also { logger.info("Successfully retrieved ${it.size} authority hints for account: $accountUsername") }
    }
}

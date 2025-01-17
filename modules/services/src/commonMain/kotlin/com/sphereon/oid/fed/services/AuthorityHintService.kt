package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.AuthorityHintDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.mappers.toDTO

class AuthorityHintService {
    private val logger = Logger.tag("AuthorityHintService")

    fun createAuthorityHint(
        account: Account,
        identifier: String
    ): AuthorityHintDTO {
        logger.debug("Attempting to create authority hint for account: ${account.username} with identifier: $identifier")
        val authorityHintAlreadyExists =
            Persistence.authorityHintQueries.findByAccountIdAndIdentifier(account.id, identifier).executeAsOneOrNull()

        if (authorityHintAlreadyExists != null) {
            val exception = EntityAlreadyExistsException(Constants.AUTHORITY_HINT_ALREADY_EXISTS)
            logger.error(
                "Authority hint already exists for account: ${account.username}, identifier: $identifier",
                exception
            )
            throw exception
        }

        return try {
            Persistence.authorityHintQueries.create(account.id, identifier)
                .executeAsOneOrNull()
                ?.toDTO()
                ?.also { logger.info("Successfully created authority hint for account: ${account.username} with identifier: $identifier") }
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error(
                "Failed to create authority hint for account: ${account.username} with identifier: $identifier",
                e
            )
            throw e
        }
    }

    fun deleteAuthorityHint(account: Account, id: Int): AuthorityHintDTO {
        logger.debug("Attempting to delete authority hint with id: $id for account: ${account.username}")

        val notFoundException = NotFoundException(Constants.AUTHORITY_HINT_NOT_FOUND)
        Persistence.authorityHintQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: run {
                logger.error(
                    "Authority hint not found with id: $id for account: ${account.username}",
                    notFoundException
                )
                throw notFoundException
            }

        return try {
            Persistence.authorityHintQueries.delete(id).executeAsOneOrNull()
                ?.toDTO()
                ?.also { logger.info("Successfully deleted authority hint with id: $id for account: ${account.username}") }
                ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error("Failed to delete authority hint with id: $id for account: ${account.username}", e)
            throw e
        }
    }

    private fun findByAccountId(accountId: Int): List<AuthorityHintDTO> {
        logger.debug("Finding authority hints for account id: $accountId")
        return Persistence.authorityHintQueries.findByAccountId(accountId).executeAsList().toDTO()
            .also { logger.debug("Found ${it.size} authority hints for account id: $accountId") }
    }

    fun findByAccount(account: Account): List<AuthorityHintDTO> {
        logger.debug("Finding authority hints for account: ${account.username}")
        return findByAccountId(account.id)
            .also { logger.info("Successfully retrieved ${it.size} authority hints for account: ${account.username}") }
    }
}

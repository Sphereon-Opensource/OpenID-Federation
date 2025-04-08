package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO

/**
 * Service class responsible for managing operations related to AuthorityHint entities.
 * Provides functionality for creating, deleting, and retrieving AuthorityHint records
 * associated with an Account.
 */
class AuthorityHintService {
    /**
     * Logger instance for the AuthorityHintService class, used to emit log messages
     * related to operations and events within this service.
     */
    private val logger = Logger.tag("AuthorityHintService")

    /**
     * Creates a new authority hint for the given account and identifier.
     * If an authority hint with the same account and identifier already exists, an exception is thrown.
     *
     * @param account The account for which the authority hint is to be created.
     * @param identifier The unique identifier for the authority hint to be created.
     * @return The created AuthorityHint instance.
     * @throws EntityAlreadyExistsException If an authority hint with the same account and identifier already exists.
     * @throws IllegalStateException If the creation of the authority hint fails for any reason.
     */
    fun createAuthorityHint(account: Account, identifier: String): AuthorityHint {
        logger.debug("Attempting to create authority hint for account: ${account.username} with identifier: $identifier")

        val existingAuthorityHint = Persistence.authorityHintQueries
            .findByAccountIdAndIdentifier(account.id, identifier)
            .executeAsOneOrNull()

        if (existingAuthorityHint != null) {
            val exception = EntityAlreadyExistsException(Constants.AUTHORITY_HINT_ALREADY_EXISTS)
            logger.error("Authority hint already exists for account: ${account.username}, identifier: $identifier", exception)
            throw exception
        }

        return try {
            Persistence.authorityHintQueries.create(account.id, identifier).executeAsOneOrNull()?.toDTO()
                ?.also { logger.info("Successfully created authority hint for account: ${account.username} with identifier: $identifier") }
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error("Failed to create authority hint for account: ${account.username} with identifier: $identifier", e)
            throw e
        }
    }

    /**
     * Deletes an AuthorityHint associated with the specified account and ID.
     *
     * @param account The account associated with the AuthorityHint to be deleted.
     * @param id The unique identifier of the AuthorityHint to delete.
     * @return The deleted AuthorityHint.
     * @throws NotFoundException if the AuthorityHint with the specified ID is not found for the provided account.
     * @throws IllegalStateException if the deletion of the AuthorityHint fails.
     */
    fun deleteAuthorityHint(account: Account, id: String): AuthorityHint {
        logger.debug("Attempting to delete authority hint with id: $id for account: ${account.username}")

        val authorityHint = Persistence.authorityHintQueries
            .findByAccountIdAndId(account.id, id)
            .executeAsOneOrNull() ?: run {
            val exception = NotFoundException(Constants.AUTHORITY_HINT_NOT_FOUND)
            logger.error("Authority hint not found with id: $id for account: ${account.username}", exception)
            throw exception
        }

        return try {
            Persistence.authorityHintQueries.delete(id)
                .executeAsOneOrNull()
                ?.toDTO()
                ?.also { logger.info("Successfully deleted authority hint with id: $id for account: ${account.username}") }
                ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_AUTHORITY_HINT)
        } catch (e: IllegalStateException) {
            logger.error("Failed to delete authority hint with id: $id for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Retrieves a list of authority hints associated with a specific account ID.
     *
     * @param accountId The unique identifier of the account whose authority hints are to be retrieved.
     * @return A list of authority hints associated with the given account ID.
     */
    private fun findByAccountId(accountId: String): List<AuthorityHint> {
        logger.debug("Finding authority hints for account id: $accountId")
        val authorityHints = Persistence.authorityHintQueries.findByAccountId(accountId)
            .executeAsList()
            .map { it.toDTO() }
        logger.debug("Found ${authorityHints.size} authority hints for account id: $accountId")
        return authorityHints
    }

    /**
     * Finds authority hints associated with the specified account.
     *
     * @param account The account for which authority hints need to be retrieved. This includes information
     * such as the account's unique identifier (`id`) and username.
     * @return A list of authority hints (`AuthorityHint`) that are associated with the provided account.
     * Each authority hint contains details such as its identifier and account association.
     */
    fun findByAccount(account: Account): List<AuthorityHint> {
        logger.debug("Finding authority hints for account: ${account.username}")
        val authorityHints = findByAccountId(account.id)
        logger.info("Successfully retrieved ${authorityHints.size} authority hints for account: ${account.username}")
        return authorityHints
    }
}
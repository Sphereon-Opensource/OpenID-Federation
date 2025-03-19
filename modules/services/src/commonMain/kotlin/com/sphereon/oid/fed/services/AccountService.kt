package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import com.sphereon.oid.fed.services.mappers.account.toDTO

/**
 * Service responsible for managing accounts and related operations.
 *
 * @param config Configuration object for account-related settings.
 */
class AccountService(
    private val config: AccountServiceConfig
) {
    /**
     * Logger instance used for logging messages and events within the AccountService class.
     * Provides a centralized logging mechanism to track the behavior and state of the service operations.
     * Tagged specifically as "AccountService" to differentiate logs originating from this class.
     */
    private val logger = Logger.tag("AccountService")
    /**
     * A private field providing access to account-specific persistence queries.
     * It interfaces with the underlying database to perform operations related
     * to accounts, such as creation, retrieval, update, and deletion.
     *
     * This field depends on the `Persistence` layer and is used by the methods
     * within the `AccountService` class to manage account-related data.
     */
    private val accountQueries = Persistence.accountQueries

    /**
     * Creates a new account with the given details.
     *
     * @param account The account details including username and identifier to create the account.
     * @return The created account object.
     * @throws EntityAlreadyExistsException If an account with the given username already exists.
     */
    fun createAccount(account: CreateAccount): Account {
        logger.info("Starting account creation process for username: ${account.username}")
        logger.debug("Account creation details - Username: ${account.username}, Identifier: ${account.identifier}")
        val existingAccount = accountQueries.findByUsername(account.username).executeAsOneOrNull()
        if (existingAccount != null) {
            logger.error("Account creation failed: Account with username ${account.username} already exists")
            throw EntityAlreadyExistsException(Constants.ACCOUNT_ALREADY_EXISTS)
        }
        val createdAccount = accountQueries.create(
            username = account.username,
            identifier = account.identifier
        ).executeAsOne()
        logger.info("Successfully created account - Username: ${account.username}, ID: ${createdAccount.id}, Identifier: ${createdAccount.identifier}")
        return createdAccount.toDTO()
    }

    /**
     * Retrieves a list of all accounts.
     *
     * The method queries the data source to fetch all stored accounts, maps
     * them into data transfer objects (DTOs), and returns them as a list.
     *
     * @return A list of all accounts as DTOs.
     */
    fun getAllAccounts(): List<Account> {
        logger.debug("Retrieving all accounts")
        val accounts = accountQueries.findAll().executeAsList()
        logger.debug("Found ${accounts.size} accounts")
        return accounts.map { it.toDTO() }
    }

    /**
     * Retrieves the account identifier for the given account. If the account has an explicit identifier,
     * it will be used. Otherwise, the identifier will be computed based on the account's username and
     * the root identifier from the configuration.
     *
     * @param account The account for which the identifier is being retrieved. The account must have a username
     *                and may have an optional explicit identifier.
     * @return The account's identifier, either explicitly defined or computed based on the username and
     *         configuration.
     */
    fun getAccountIdentifierByAccount(account: Account): String {
        account.identifier?.let {
            logger.debug("Found explicit identifier for username: ${account.username}")
            return it
        }
        check(config.rootIdentifier.isNotBlank() || config.rootIdentifier.isNotEmpty()) { "Root identifier is not configured" }
        val computedIdentifier = if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            config.rootIdentifier
        } else {
            "${config.rootIdentifier}/${account.username}"
        }
        logger.debug("Using identifier for username: ${account.username}: $computedIdentifier")
        return computedIdentifier
    }

    /**
     * Retrieves an account by the specified username.
     * Throws a NotFoundException if no account exists with the given username.
     *
     * @param username The username to search for.
     * @return The account associated with the provided username.
     * @throws NotFoundException if the account is not found.
     */
    fun getAccountByUsername(username: String): Account {
        logger.debug("Getting account by username: $username")
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()?.toDTO()
        return account ?: run {
            logger.error("Account not found for username: $username")
            throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
        }
    }

    /**
     * Deletes the specified account from the system.
     *
     * @param account The account to be deleted. This includes the account's unique identifier and username.
     * @return The deleted account represented as a DTO (Data Transfer Object).
     * @throws NotFoundException if the account to be deleted is the root account.
     */
    fun deleteAccount(account: Account): Account {
        logger.info("Starting account deletion process for username: ${account.username}")
        logger.debug("Account deletion details - Username: ${account.username}, ID: ${account.id}")
        assertNonRootAccount(account)
        val deletedAccount = accountQueries.delete(account.id).executeAsOne()
        logger.info("Successfully deleted account - Username: ${account.username}, ID: ${account.id}")
        return deletedAccount.toDTO()
    }

    /**
     * Ensures that the provided account is not the root account.
     * If the account matches the default root username, an error is logged, and a NotFoundException is thrown.
     *
     * @param account The account object to check against the root account criteria.
     *                Contains information such as the username of the account.
     * @throws NotFoundException if the provided account is the root account.
     */
    private fun assertNonRootAccount(account: Account) {
        if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            logger.error("Account deletion failed: Attempted to delete root account")
            throw NotFoundException(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED)
        }
    }
}
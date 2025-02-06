package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.config.AccountServiceConfig
import com.sphereon.oid.fed.services.mappers.toDTO

class AccountService(
    private val config: AccountServiceConfig
) {
    private val logger = Logger.tag("AccountService")
    private val accountQueries = Persistence.accountQueries

    fun createAccount(account: CreateAccount): Account {
        logger.info("Starting account creation process for username: ${account.username}")
        logger.debug("Account creation details - Username: ${account.username}, Identifier: ${account.identifier}")

        val accountAlreadyExists = accountQueries.findByUsername(account.username).executeAsOneOrNull()

        if (accountAlreadyExists != null) {
            logger.error("Account creation failed: Account with username ${account.username} already exists")
            throw EntityAlreadyExistsException(Constants.ACCOUNT_ALREADY_EXISTS)
        }

        val createdAccount = accountQueries.create(
            username = account.username,
            identifier = account.identifier,
        ).executeAsOne()
        logger.info("Successfully created account - Username: ${account.username}, ID: ${createdAccount.id}, Identifier: ${createdAccount.identifier}")
        return createdAccount.toDTO()
    }

    fun getAllAccounts(): List<Account> {
        logger.debug("Retrieving all accounts")
        val accounts = accountQueries.findAll().executeAsList()
        logger.debug("Found ${accounts.size} accounts")
        return accounts.map { it.toDTO() }
    }

    fun getAccountIdentifierByAccount(account: Account): String {
        account.identifier?.let { identifier ->
            logger.debug("Found explicit identifier for username: ${account.username}")
            return identifier
        }

        // For root account, return root identifier directly
        val identifier = if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            config.rootIdentifier
        } else {
            "${config.rootIdentifier}/${account.username}"
        }
        logger.debug("Using identifier for username: ${account.username} as $identifier")
        return identifier
    }

    fun getAccountByUsername(username: String): Account {
        logger.debug("Getting account by username: $username")
        return accountQueries.findByUsername(username).executeAsOneOrNull()?.toDTO()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }
    }

    fun deleteAccount(account: Account): Account {
        logger.info("Starting account deletion process for username: ${account.username}")
        logger.debug("Account deletion details - Username: ${account.username}, ID: ${account.id}")

        if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            logger.error("Account deletion failed: Attempted to delete root account")
            throw NotFoundException(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED)
        }

        val deletedAccount = accountQueries.delete(account.id).executeAsOne()
        logger.info("Successfully deleted account - Username: ${account.username}, ID: ${account.id}")
        return deletedAccount.toDTO()
    }
}
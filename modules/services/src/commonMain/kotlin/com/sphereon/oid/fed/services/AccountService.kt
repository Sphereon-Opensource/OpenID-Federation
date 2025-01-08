package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.extensions.toAccountDTO

class AccountService() {
    private val logger = Logger.tag("AccountService")
    private val accountQueries = Persistence.accountQueries

    fun create(account: CreateAccountDTO): AccountDTO {
        logger.info("Creating new account with username: ${account.username}")
        val accountAlreadyExists = accountQueries.findByUsername(account.username).executeAsOneOrNull()

        if (accountAlreadyExists != null) {
            logger.error("Account creation failed: Account with username ${account.username} already exists")
            throw EntityAlreadyExistsException(Constants.ACCOUNT_ALREADY_EXISTS)
        }

        val createdAccount = accountQueries.create(
            username = account.username,
            identifier = account.identifier,
        ).executeAsOne().toAccountDTO()
        logger.info("Successfully created account with username: ${account.username}")
        return createdAccount
    }

    fun findAll(): List<AccountDTO> {
        logger.debug("Retrieving all accounts")
        val accounts = accountQueries.findAll().executeAsList().map { it.toAccountDTO() }
        logger.debug("Found ${accounts.size} accounts")
        return accounts
    }

    fun getAccountIdentifier(username: String): String {
        logger.debug("Getting account identifier for username: $username")
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }

        val identifier = account.identifier

        if (identifier != null) {
            logger.debug("Found explicit identifier for username: $username")
            return identifier
        }

        val rootIdentifier =
            System.getenv("ROOT_IDENTIFIER") ?: throw NotFoundException(Constants.ROOT_IDENTIFIER_NOT_SET).also {
                logger.error("ROOT_IDENTIFIER environment variable not set")
            }

        if (username == "root") {
            logger.debug("Using root identifier for root account")
            return rootIdentifier
        }

        logger.debug("Generated identifier for username: $username")
        return "$rootIdentifier/$username"
    }

    fun getAccountByUsername(username: String): Account {
        logger.debug("Getting account by username: $username")
        return accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }
    }

    fun deleteAccount(username: String): Account {
        logger.info("Attempting to delete account with username: $username")
        if (username == "root") {
            logger.error("Attempted to delete root account")
            throw NotFoundException(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED)
        }

        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }

        val deletedAccount = accountQueries.delete(account.id).executeAsOne()
        logger.info("Successfully deleted account with username: $username")
        return deletedAccount
    }

    fun usernameToAccountId(username: String): Int {
        logger.debug("Converting username to account ID: $username")
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }

        logger.debug("Found account ID ${account.id} for username: $username")
        return account.id
    }
}

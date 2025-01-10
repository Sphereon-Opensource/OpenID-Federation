package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.config.AccountConfig
import com.sphereon.oid.fed.services.mappers.toAccountDTO

class AccountService(
    private val config: AccountConfig
) {
    private val logger = Logger.tag("AccountService")
    private val accountQueries = Persistence.accountQueries

    fun createAccount(account: CreateAccountDTO): AccountDTO {
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

    fun getAllAccounts(): List<AccountDTO> {
        logger.debug("Retrieving all accounts")
        val accounts = accountQueries.findAll().executeAsList().map { it.toAccountDTO() }
        logger.debug("Found ${accounts.size} accounts")
        return accounts
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
        return accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found for username: $username")
            }
    }

    fun deleteAccount(account: Account): AccountDTO {
        logger.info("Attempting to delete account with username: ${account.username}")
        if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
            logger.error("Attempted to delete root account")
            throw NotFoundException(Constants.ROOT_ACCOUNT_CANNOT_BE_DELETED)
        }

        val deletedAccount = accountQueries.delete(account.id).executeAsOne()
        logger.info("Successfully deleted account with username: ${account.username}")
        return deletedAccount.toAccountDTO()
    }
}
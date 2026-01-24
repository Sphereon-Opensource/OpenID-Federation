package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.services.command.account.*

/**
 * Service interface responsible for managing accounts and related operations.
 *
 * This service aggregates all account-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface AccountService :
    CreateAccountCommandService,
    GetAllAccountsCommandService,
    GetAccountByUsernameCommandService,
    GetAccountIdentifierCommandService,
    DeleteAccountCommandService {

    val commands: Commands

    interface Commands {
        val createAccount: CreateAccountCommand
        val getAllAccounts: GetAllAccountsCommand
        val getAccountByUsername: GetAccountByUsernameCommand
        val getAccountIdentifier: GetAccountIdentifierCommand
        val deleteAccount: DeleteAccountCommand
    }

    override suspend fun createAccount(account: CreateAccount): FederationResult<Account>
    override suspend fun getAllAccounts(): FederationResult<List<Account>>
    override suspend fun getAccountByUsername(username: String): FederationResult<Account>
    override suspend fun getAccountIdentifierByAccount(account: Account): FederationResult<String>
    override suspend fun deleteAccount(account: Account): FederationResult<Account>
}

package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.services.command.account.*
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = AccountService::class)
class AccountServiceImpl(
    private val createAccountCommand: CreateAccountCommand,
    private val getAllAccountsCommand: GetAllAccountsCommand,
    private val getAccountByUsernameCommand: GetAccountByUsernameCommand,
    private val getAccountIdentifierCommand: GetAccountIdentifierCommand,
    private val deleteAccountCommand: DeleteAccountCommand
) : AccountService {

    override suspend fun createAccount(account: CreateAccount): FederationResult<Account> =
        createAccountCommand.execute(account).toFederationResult()

    override suspend fun getAllAccounts(): FederationResult<List<Account>> =
        getAllAccountsCommand.execute(Unit).toFederationResult()

    override suspend fun getAccountByUsername(username: String): FederationResult<Account> =
        getAccountByUsernameCommand.execute(GetAccountByUsernameArgs(username)).toFederationResult()

    override suspend fun getAccountIdentifierByAccount(account: Account): FederationResult<String> =
        getAccountIdentifierCommand.execute(GetAccountIdentifierArgs(account)).toFederationResult()

    override suspend fun deleteAccount(account: Account): FederationResult<Account> =
        deleteAccountCommand.execute(DeleteAccountArgs(account)).toFederationResult()
}

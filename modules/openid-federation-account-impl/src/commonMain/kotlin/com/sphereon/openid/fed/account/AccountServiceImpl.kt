package com.sphereon.openid.fed.account

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount
import com.sphereon.openid.fed.account.command.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AccountService>())
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

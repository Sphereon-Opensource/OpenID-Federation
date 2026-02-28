package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateAccount

interface AccountService {

    suspend fun createAccount(account: CreateAccount): FederationResult<Account>
    suspend fun getAllAccounts(): FederationResult<List<Account>>
    suspend fun getAccountByUsername(username: String): FederationResult<Account>
    suspend fun getAccountIdentifierByAccount(account: Account): FederationResult<String>
    suspend fun deleteAccount(account: Account): FederationResult<Account>
}

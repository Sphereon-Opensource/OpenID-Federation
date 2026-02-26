package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.services.AccountService
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.di.session.SessionScope

/**
 * Resolves the current account from HTTP request headers.
 *
 * This component extracts the account identifier from the request headers
 * and resolves it to a full Account object using the AccountService.
 */
@Inject
@SingleIn(SessionScope::class)
class AccountResolver(
    private val accountService: AccountService
) {
    /**
     * Resolves the account from the request headers.
     *
     * @param request The incoming HTTP request
     * @return The resolved Account, or null if resolution fails
     */
    suspend fun resolveAccount(request: GenericHttpRequest): Account? {
        val accountUsername = request.headers[Constants.ACCOUNT_HEADER]
            ?: request.headers[Constants.ACCOUNT_HEADER.lowercase()]
            ?: "root"

        return try {
            val result = accountService.getAccountByUsername(accountUsername)
            if (result.isOk) result.value else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the account identifier (URL) from the account.
     *
     * @param account The account to get the identifier for
     * @return The account identifier URL, or null if resolution fails
     */
    suspend fun resolveAccountIdentifier(account: Account): String? {
        return try {
            val result = accountService.getAccountIdentifierByAccount(account)
            if (result.isOk) result.value else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves both account and its identifier from the request.
     *
     * @param request The incoming HTTP request
     * @return Pair of (Account, Identifier) or null if either fails
     */
    suspend fun resolveAccountWithIdentifier(request: GenericHttpRequest): Pair<Account, String>? {
        val account = resolveAccount(request) ?: return null
        val identifier = resolveAccountIdentifier(account) ?: return null
        return account to identifier
    }
}

package com.sphereon.openid.fed.server.http.command

import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.di.session.SessionScope

/**
 * Resolves the current account from HTTP request path parameters.
 *
 * For the Federation Server, accounts are resolved from the path parameter
 * (e.g., /{username}/.well-known/openid-federation) or default to root.
 */
@Inject
@SingleIn(SessionScope::class)
class FederationAccountResolver(
    private val accountService: AccountService
) {
    private val accountQueries = Persistence.accountQueries

    /**
     * Resolves the account from path parameters.
     *
     * If the path contains a {username} parameter, that account is used.
     * Otherwise, the default root account is used.
     *
     * @param request The incoming HTTP request
     * @param pathPattern The path pattern to extract parameters from
     * @return The resolved Account, or null if not found
     */
    suspend fun resolveAccount(request: GenericHttpRequest, pathPattern: String): Account? {
        val requestWithParams = request.withExtractedParams(pathPattern)
        val username = requestWithParams.pathParams["username"] ?: Constants.DEFAULT_ROOT_USERNAME

        return try {
            accountQueries.findByUsername(username).executeAsOneOrNull()?.toDTO()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the account from a username string.
     *
     * @param username The username to resolve
     * @return The resolved Account, or null if not found
     */
    suspend fun resolveAccountByUsername(username: String): Account? {
        return try {
            accountQueries.findByUsername(username).executeAsOneOrNull()?.toDTO()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves the account identifier (URL) for an account.
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
}

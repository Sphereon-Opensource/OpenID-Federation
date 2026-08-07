package com.sphereon.openid.fed.account

import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.account.error.AccountConstants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * LEGACY-mode tenant resolution via Account DB lookup.
 *
 * Uses the X-Account-Username header to resolve an account, then uses
 * the account's ID as the tenantId. Bound only indirectly through
 * [ModeAwareTenantContextResolver] (not as the sole [TenantContextResolver]).
 */
@Inject
@SingleIn(SessionScope::class)
class AccountBasedTenantContextResolver(
    private val accountService: AccountService,
    private val config: TenantServiceConfig
) : TenantContextResolver {

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? {
        val username = request.headers[AccountConstants.ACCOUNT_HEADER]
            ?: request.headers[AccountConstants.ACCOUNT_HEADER.lowercase()]
            ?: Constants.DEFAULT_ROOT_USERNAME

        return try {
            val result = accountService.getAccountByUsername(username)
            if (result.isOk) result.value.id else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun resolveTenantIdByName(name: String): String? {
        return try {
            val result = accountService.getAccountByUsername(name)
            if (result.isOk) result.value.id else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun resolveIdentifier(tenantId: String): String? {
        return try {
            // Look up the account to get username for identifier computation
            val accounts = accountService.getAllAccounts()
            if (accounts.isOk) {
                val account = accounts.value.find { it.id == tenantId }
                if (account != null) {
                    // Use explicit identifier if set
                    account.identifier?.let { return it }

                    // Compute from rootIdentifier
                    if (config.rootIdentifier.isBlank()) return null
                    if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
                        config.rootIdentifier
                    } else {
                        "${config.rootIdentifier}/${account.username}"
                    }
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

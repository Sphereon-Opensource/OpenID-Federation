package com.sphereon.openid.fed.account

import com.sphereon.openid.fed.account.error.AccountConstants
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.persistence.Persistence

/**
 * Synchronous Account.id lookup for IDK session tenant alignment (L2).
 *
 * ## Boundary
 * IDK [com.sphereon.ktor.server.inject.resolver.TenantResolver.resolve] is **synchronous**.
 * Business [TenantContextResolver] is session-based. This helper is for **admin ingress**
 * (JWT-first open / ACCOUNT rebind) only — maps username → Account.id.
 *
 * Only use when [com.sphereon.openid.fed.core.tenant.IdentityMode.ACCOUNT].
 */
object LegacyAccountSessionTenantLookup {

    /**
     * Resolve federation Account.id for a username.
     *
     * @param username Account username; blank uses [Constants.DEFAULT_ROOT_USERNAME]
     * @return Account UUID string, or null if not found / DB unavailable
     */
    fun resolveAccountId(username: String?): String? {
        val name = username?.trim()?.takeIf { it.isNotEmpty() }
            ?: Constants.DEFAULT_ROOT_USERNAME
        return try {
            Persistence.accountQueries.findByUsername(name).executeAsOneOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract legacy account username from HTTP header map (case-insensitive keys).
     *
     * Defaults to [Constants.DEFAULT_ROOT_USERNAME] when the header is absent.
     * Prefer [com.sphereon.openid.fed.core.tenant.AccountEntityHeaderAuth] when enforcing
     * **root-only** header switch (do not use this default for authorization decisions).
     */
    fun usernameFromHeaders(headers: (String) -> String?): String {
        return headers(AccountConstants.ACCOUNT_HEADER)
            ?: headers(AccountConstants.ACCOUNT_HEADER.lowercase())
            ?: headers(Constants.ACCOUNT_HEADER)
            ?: headers(Constants.ACCOUNT_HEADER.lowercase())
            ?: Constants.DEFAULT_ROOT_USERNAME
    }

    /**
     * Header value only — null when the client did not send entity selection.
     */
    fun rawUsernameFromHeaders(headers: (String) -> String?): String? {
        return headers(AccountConstants.ACCOUNT_HEADER)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: headers(AccountConstants.ACCOUNT_HEADER.lowercase())
                ?.trim()?.takeIf { it.isNotEmpty() }
            ?: headers(Constants.ACCOUNT_HEADER)
                ?.trim()?.takeIf { it.isNotEmpty() }
            ?: headers(Constants.ACCOUNT_HEADER.lowercase())
                ?.trim()?.takeIf { it.isNotEmpty() }
    }
}

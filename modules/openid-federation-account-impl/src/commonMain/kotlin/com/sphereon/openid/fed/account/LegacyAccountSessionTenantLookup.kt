package com.sphereon.openid.fed.account

import com.sphereon.openid.fed.account.error.AccountConstants
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.persistence.Persistence

/**
 * Synchronous Account.id lookup for IDK session tenant alignment (L2).
 *
 * ## Boundary
 * IDK [com.sphereon.ktor.server.inject.resolver.TenantResolver.resolve] is **synchronous**.
 * Business [com.sphereon.openid.fed.core.tenant.TenantContextResolver] remains suspend-based.
 * This helper bridges LEGACY multi-entity selection (`X-Account-Username`) to a tenant id
 * string suitable for [com.sphereon.core.defaults.context.DefaultTenantInputString].
 *
 * Uses SQLDelight queries directly (same DB as Account commands) — not header spoofing
 * of platform tenants: only call this path when [com.sphereon.openid.fed.core.tenant.IdentityMode.LEGACY].
 *
 * @see ModeAwareTenantContextResolver for business-layer resolution (must stay consistent)
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
     */
    fun usernameFromHeaders(headers: (String) -> String?): String {
        return headers(AccountConstants.ACCOUNT_HEADER)
            ?: headers(AccountConstants.ACCOUNT_HEADER.lowercase())
            ?: headers(Constants.ACCOUNT_HEADER)
            ?: headers(Constants.ACCOUNT_HEADER.lowercase())
            ?: Constants.DEFAULT_ROOT_USERNAME
    }
}

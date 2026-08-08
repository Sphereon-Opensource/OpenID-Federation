package com.sphereon.openid.fed.core.tenant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ACCOUNT-mode only: who may use `X-Account-Username` to select federation entity context.
 *
 * ## Policy
 * - **EXTERNAL mode**: headers are never identity — always deny header use.
 * - **ACCOUNT mode**: principal from [IdentityConfig.accountHeaderPrincipalClaim] must
 *   appear in [IdentityConfig.accountHeaderAllowedPrincipals].
 *   - **Empty allow-list (default)** → no one may use the header (fail closed).
 *   - Explicit `*` → any authenticated principal (test/dev only).
 *   - Explicit values → only those principals may rebind to another Account.
 *
 * Configured on the OIDFed side — not an AS convention like hard-coded `sub=root`.
 */
object AccountEntityHeaderAuth {

    const val ACCOUNT_HEADER = "X-Account-Username"
    const val ROOT_USERNAME = "root"

    /**
     * Principal id from validated claims using the configured claim name.
     */
    fun principalFromClaims(
        claims: Map<String, JsonElement>?,
        claimName: String,
    ): String? {
        if (claims == null || claims.isEmpty()) return null
        val name = claimName.trim().ifEmpty { "sub" }
        return claims[name]?.stringContent()?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (name != "sub") claims["sub"]?.stringContent()?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    /**
     * Whether this authenticated principal may send [ACCOUNT_HEADER] in ACCOUNT mode.
     */
    fun mayUseEntityHeader(
        claims: Map<String, JsonElement>?,
        identity: IdentityConfig,
    ): Boolean {
        if (!identity.isAccount) return false
        val principal = principalFromClaims(claims, identity.accountHeaderPrincipalClaim)
        if (principal.isNullOrBlank()) {
            // Authenticated token with no usable principal claim: only allow if wildcard
            return identity.accountHeaderAllowsAnyAuthenticated && claims != null && claims.isNotEmpty()
        }
        if (identity.accountHeaderAllowsAnyAuthenticated) return true
        return identity.accountHeaderAllowedPrincipals.any { it.trim() == principal }
    }

    fun entityUsernameFromHeaders(headers: (String) -> String?): String? {
        val raw =
            headers(ACCOUNT_HEADER)
                ?: headers(ACCOUNT_HEADER.lowercase())
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun hasEntitySelectionHeader(headers: (String) -> String?): Boolean =
        entityUsernameFromHeaders(headers) != null

    /**
     * Effective entity username (ACCOUNT mode):
     * - header present → that username (caller must have checked [denyReasonIfHeaderForbidden])
     * - no header → [ROOT_USERNAME] when allow-list permits this principal (default entity)
     * - no header and principal not allowed for default root → principal string if it looks like a username, else null
     */
    fun effectiveEntityUsername(
        claims: Map<String, JsonElement>?,
        headers: (String) -> String?,
        identity: IdentityConfig,
    ): String? {
        val headerUser = entityUsernameFromHeaders(headers)
        if (headerUser != null) return headerUser
        // Default entity context for account mode: seeded root account
        if (mayUseEntityHeader(claims, identity)) return ROOT_USERNAME
        return principalFromClaims(claims, identity.accountHeaderPrincipalClaim)
    }

    /**
     * @return null if allowed; otherwise denial reason for 403
     */
    fun denyReasonIfHeaderForbidden(
        claims: Map<String, JsonElement>?,
        headers: (String) -> String?,
        identity: IdentityConfig,
    ): String? {
        if (!hasEntitySelectionHeader(headers)) return null
        if (!identity.isAccount) {
            return "$ACCOUNT_HEADER is not used for identity in EXTERNAL mode; tenant comes from the access token only"
        }
        if (mayUseEntityHeader(claims, identity)) return null
        val claim = identity.accountHeaderPrincipalClaim
        val principal = principalFromClaims(claims, claim) ?: "<missing>"
        return "Principal '$principal' (claim '$claim') is not allowed to use $ACCOUNT_HEADER. " +
            "Configure oidf.identity.account.header.allowed.principals " +
            "(current allow-list: ${identity.accountHeaderAllowedPrincipals.joinToString()})"
    }

    private fun JsonElement.stringContent(): String? =
        try {
            jsonPrimitive.contentOrNull
        } catch (_: Exception) {
            null
        }
}

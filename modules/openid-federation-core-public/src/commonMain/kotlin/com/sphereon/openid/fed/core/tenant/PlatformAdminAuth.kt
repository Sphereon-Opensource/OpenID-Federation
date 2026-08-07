package com.sphereon.openid.fed.core.tenant

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.error.UnauthorizedError
import com.sphereon.openid.fed.core.error.federationErr

/**
 * Fail-closed auth checks for PLATFORM identity mode admin mutations.
 *
 * ## Mode boundary
 * | Mode | Behaviour |
 * |------|-----------|
 * | **LEGACY** | No-op here — historical header-selected account; optional OAuth is separate |
 * | **PLATFORM** | Requires a non-anonymous IDK session unless `oidf.identity.allow.anonymous.admin=true` |
 *
 * PLATFORM does **not** accept caller-controlled `X-Tenant-Id` / `X-Principal-Id` as identity;
 * the host must populate session via JWT (or FixedTenantResolver for single-tenant embeds).
 *
 * Apply [denyUnlessAdminAllowed] at the start of admin HTTP mutation handlers (POST/PUT/DELETE).
 * Reads may remain more permissive depending on product policy.
 */
object PlatformAdminAuth {

    /**
     * HTTP endpoint guard.
     *
     * @return `null` if the call is allowed; otherwise a ready-to-return 401 [IdkResult]
     */
    fun denyUnlessAdminAllowed(
        execution: SessionExecution,
        configBinder: OidfConfigBinder,
    ): IdkResult<GenericHttpResponse, IdkError>? {
        val identity = configBinder.getIdentityConfig()
        // LEGACY: account header model; do not enforce session principal here.
        if (!identity.isPlatform) return null
        // Explicit escape hatch for local single-tenant embeds without JWT.
        if (identity.allowAnonymousAdmin) return null
        if (!execution.isAnonymous()) return null

        return Ok(
            errorResponse(
                401,
                "Authentication required: PLATFORM identity mode rejects anonymous admin mutations. " +
                    "Provide a validated host JWT/session, or set oidf.identity.allow.anonymous.admin=true for local embeds.",
            ),
        )
    }

    /**
     * Service-layer guard returning [UnauthorizedError] (for command impls not returning HTTP).
     *
     * @return `null` if allowed; otherwise [federationErr] with [UnauthorizedError]
     */
    fun unauthorizedUnlessAdminAllowed(
        execution: SessionExecution,
        configBinder: OidfConfigBinder,
    ): IdkResult<Nothing, com.sphereon.openid.fed.core.error.FederationError>? {
        val identity = configBinder.getIdentityConfig()
        if (!identity.isPlatform) return null
        if (identity.allowAnonymousAdmin) return null
        if (!execution.isAnonymous()) return null
        return federationErr(
            UnauthorizedError(
                "PLATFORM identity mode requires an authenticated session for admin operations",
            ),
        )
    }
}

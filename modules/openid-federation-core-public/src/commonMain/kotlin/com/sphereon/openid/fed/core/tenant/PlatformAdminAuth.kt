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
 * Fail-closed auth checks for admin mutations.
 *
 * ## Policy
 * Admin always requires Bearer at the JWT plugin (`requireAuth=true`, issuer required).
 *
 * | Mode | Mutation guard |
 * |------|----------------|
 * | **ACCOUNT** | No-op here — JWT + optional header rebind at ingress |
 * | **EXTERNAL** | Rejects anonymous DI sessions; tenant must come from the access token |
 *
 * Apply [denyUnlessAdminAllowed] at the start of admin HTTP mutation handlers (POST/PUT/DELETE).
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
        if (!identity.isExternal) return null

        if (!execution.isAnonymous()) return null

        return Ok(
            errorResponse(
                401,
                "Authentication required: EXTERNAL admin rejects anonymous sessions. " +
                    "Provide a validated Bearer access token with tenant claims.",
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
        if (denyUnlessAdminAllowed(execution, configBinder) == null) return null
        return federationErr(
            UnauthorizedError(
                "EXTERNAL admin operations require an authenticated session with tenant claims",
            ),
        )
    }
}

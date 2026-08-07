package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.PlatformAdminAuth
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Session-scoped guard for admin HTTP **mutations** (POST/PUT/DELETE).
 *
 * Admin is always authenticated (LEGACY and PLATFORM). Rejects anonymous sessions
 * as defense in depth after the JWT plugin. Tenant selection remains mode-specific
 * (`X-Account-Username` vs JWT tenant claims).
 *
 * Inject into mutation endpoint command impls and call [denyIfUnauthorized] as the
 * first line of [com.sphereon.core.api.http.command.HttpEndpointCommandAdapter.doExecute]:
 *
 * ```kotlin
 * adminMutationGuard.denyIfUnauthorized()?.let { return it }
 * ```
 *
 * Do **not** apply to pure read/list GET endpoints unless product policy requires it.
 *
 * @see PlatformAdminAuth
 */
@Inject
@SingleIn(SessionScope::class)
class AdminMutationGuard(
    private val execution: SessionExecution,
    private val configBinder: OidfConfigBinder,
) {
    /**
     * @return `null` if the mutation may proceed; otherwise a ready-to-return HTTP error result.
     */
    fun denyIfUnauthorized(): IdkResult<GenericHttpResponse, IdkError>? =
        PlatformAdminAuth.denyUnlessAdminAllowed(execution, configBinder)
}

package com.sphereon.openid.fed.account

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.di.context.IdentityConstants
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * ACCOUNT-mode [TenantContextResolver]: federation entity context from the **DI session**.
 *
 * ## Resolution
 * [resolveTenantId] uses [SessionExecution.tenantId] only — set at JWT-first open and
 * optionally updated by admin ACCOUNT header rebind. Does **not** re-read
 * `X-Account-Username` so business isolation cannot diverge from KMS/config/cache scopes.
 *
 * [resolveTenantIdByName] / [resolveIdentifier] still use Account rows (path-based public
 * entity selection and entity URL mapping).
 *
 * Bound only via [ModeAwareTenantContextResolver].
 */
@Inject
@SingleIn(SessionScope::class)
class AccountBasedTenantContextResolver(
    private val execution: SessionExecution,
    private val accountService: AccountService,
    private val config: TenantServiceConfig,
) : TenantContextResolver {

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? {
        // Single source after JWT open + optional header rebind (admin ingress).
        val tenantId = execution.tenantId.takeUnless { it.isAnonymousTenant() } ?: return null
        // Bootstrap fixed session id is not a federation Account row — map to seeded root Account.
        if (tenantId == "default") {
            return resolveRootAccountId() ?: tenantId
        }
        return tenantId
    }

    private suspend fun resolveRootAccountId(): String? =
        try {
            val result = accountService.getAccountByUsername(Constants.DEFAULT_ROOT_USERNAME)
            if (result.isOk) result.value.id else null
        } catch (_: Exception) {
            null
        }

    override suspend fun resolveTenantIdByName(name: String): String? {
        return try {
            val result = accountService.getAccountByUsername(name)
            if (result.isOk) result.value.id else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun resolveIdentifier(tenantId: String): String? {
        return try {
            val accounts = accountService.getAllAccounts()
            if (accounts.isOk) {
                val account = accounts.value.find { it.id == tenantId }
                if (account != null) {
                    account.identifier?.let { return it }

                    if (config.rootIdentifier.isBlank()) return null
                    if (account.username == Constants.DEFAULT_ROOT_USERNAME) {
                        config.rootIdentifier
                    } else {
                        "${config.rootIdentifier}/${account.username}"
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.isAnonymousTenant(): Boolean =
        this == IdentityConstants.ANONYMOUS_TENANT_ID || this.equals("anonymous", ignoreCase = true)
}

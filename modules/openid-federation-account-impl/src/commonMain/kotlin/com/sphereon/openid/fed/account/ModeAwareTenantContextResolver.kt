package com.sphereon.openid.fed.account

import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Single DI binding for [TenantContextResolver] that delegates by [IdentityMode].
 *
 * - [IdentityMode.LEGACY] → [AccountBasedTenantContextResolver]
 * - [IdentityMode.PLATFORM] → [SessionTenantContextResolver]
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TenantContextResolver>())
class ModeAwareTenantContextResolver(
    private val configBinder: OidfConfigBinder,
    private val accountBased: AccountBasedTenantContextResolver,
    private val sessionBased: SessionTenantContextResolver
) : TenantContextResolver {

    private val delegate: TenantContextResolver
        get() = when (configBinder.getIdentityConfig().mode) {
            IdentityMode.LEGACY -> accountBased
            IdentityMode.PLATFORM -> sessionBased
        }

    override suspend fun resolveTenantId(request: GenericHttpRequest): String? =
        delegate.resolveTenantId(request)

    override suspend fun resolveTenantIdByName(name: String): String? =
        delegate.resolveTenantIdByName(name)

    override suspend fun resolveIdentifier(tenantId: String): String? =
        delegate.resolveIdentifier(tenantId)
}

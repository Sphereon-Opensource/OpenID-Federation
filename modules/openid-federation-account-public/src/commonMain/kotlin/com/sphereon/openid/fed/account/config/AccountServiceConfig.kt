package com.sphereon.openid.fed.account.config

import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Configuration class for account-related settings.
 * Wraps TenantServiceConfig for backward compatibility.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<IAccountServiceConfig>())
class AccountServiceConfig(tenantConfig: TenantServiceConfig) : IAccountServiceConfig {
    override val rootIdentifier: String = tenantConfig.rootIdentifier

    fun toTenantServiceConfig(): TenantServiceConfig = TenantServiceConfig(rootIdentifier)
}

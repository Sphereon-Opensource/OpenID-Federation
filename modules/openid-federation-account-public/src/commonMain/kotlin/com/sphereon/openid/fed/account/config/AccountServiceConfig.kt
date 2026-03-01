package com.sphereon.openid.fed.account.config

import com.sphereon.openid.fed.core.tenant.TenantServiceConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Configuration class for account-related settings.
 * Wraps TenantServiceConfig for backward compatibility.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = IAccountServiceConfig::class)
class AccountServiceConfig(tenantConfig: TenantServiceConfig) : IAccountServiceConfig {
    override val rootIdentifier: String = tenantConfig.rootIdentifier

    fun toTenantServiceConfig(): TenantServiceConfig = TenantServiceConfig(rootIdentifier)
}

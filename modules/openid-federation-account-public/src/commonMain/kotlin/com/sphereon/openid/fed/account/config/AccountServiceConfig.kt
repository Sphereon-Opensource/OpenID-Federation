package com.sphereon.openid.fed.account.config

import com.sphereon.openid.fed.core.tenant.TenantServiceConfig

/**
 * Configuration class for account-related settings.
 * Wraps TenantServiceConfig for backward compatibility.
 */
class AccountServiceConfig(override val rootIdentifier: String) : IAccountServiceConfig {
    constructor(tenantConfig: TenantServiceConfig) : this(tenantConfig.rootIdentifier)

    fun toTenantServiceConfig(): TenantServiceConfig = TenantServiceConfig(rootIdentifier)
}

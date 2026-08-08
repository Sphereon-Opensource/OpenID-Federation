package com.sphereon.openid.fed.core.tenant

/**
 * Tenant-level configuration. Replaces IAccountServiceConfig.
 *
 * @property rootIdentifier The root identifier URL for the federation entity.
 */
data class TenantServiceConfig(val rootIdentifier: String)

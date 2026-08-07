package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.ConfigService
import com.sphereon.openid.fed.core.config.OidfConfigBinder

/**
 * Resolve the KMS provider id for a tenant.
 *
 * Precedence:
 * 1. Explicit [explicitProviderId] when non-blank (API request body)
 * 2. [OidfConfigBinder.getEffectiveKmsConfig] — session tenant config bare key,
 *    then `oidf.tenant.<id>.kms.provider`, then APP `oidf.kms.default.provider`
 */
fun OidfConfigBinder.resolveEffectiveKmsProviderId(
    tenantId: String?,
    explicitProviderId: String? = null,
    sessionTenantConfig: ConfigService? = null,
): String {
    val explicit = explicitProviderId?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) return explicit
    return getEffectiveKmsConfig(tenantId, sessionTenantConfig).defaultProvider
        .ifBlank { "memory" }
}

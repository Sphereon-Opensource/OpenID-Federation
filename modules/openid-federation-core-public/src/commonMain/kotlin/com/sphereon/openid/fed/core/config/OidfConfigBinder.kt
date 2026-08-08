package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.ConfigService
import com.sphereon.openid.fed.core.tenant.IdentityConfig

/**
 * Typed configuration binder for OpenID Federation.
 *
 * ## Scopes
 * - **APP (process):** ports, datasource, identity **mode**, OAuth2 issuer install, CORS, logger.
 * - **Tenant-overridable:** federation root entity URL, default KMS provider, cache localities,
 *   ACCOUNT header allow-list claim/principals. Use [getEffective*] with [tenantId] and optionally
 *   a session [ConfigService] (IDK [com.sphereon.core.api.conf.TenantConfigService]) so bare keys
 *   on tenant property sources win over APP defaults.
 *
 * Implementations resolve APP keys through [OidfPropertyResolution]. Product code must not call
 * `System.getenv` for these settings.
 */
interface OidfConfigBinder {

    // ========================================================================
    // APP Scope Configuration (process-fixed)
    // ========================================================================

    fun getFederationConfig(): FederationConfig

    fun getServerConfig(type: ServerType): ServerConfig

    fun getCorsConfig(): CorsConfig

    fun getLoggerConfig(): LoggerConfig

    fun getDatasourceConfig(): DatasourceConfig

    fun getOAuth2Config(): OAuth2Config

    fun getKmsConfig(): KmsConfig

    fun getIdentityConfig(): IdentityConfig

    fun getAppConfig(): OidfAppConfig

    // ========================================================================
    // Tenant-overridable configuration
    // ========================================================================

    /**
     * Explicit tenant overrides from `oidf.tenant.<tenantId>.*` (and session sources when loaded).
     * Returns null if no tenant-level overrides are present for [tenantId].
     */
    fun getTenantConfig(tenantId: String): TenantConfig?

    /**
     * Effective federation config for [tenantId].
     * Precedence: session tenant config (bare keys) → `oidf.tenant.<id>.*` → APP.
     */
    fun getEffectiveFederationConfig(
        tenantId: String?,
        sessionTenantConfig: ConfigService? = null,
    ): FederationConfig {
        if (tenantId.isNullOrBlank()) return getFederationConfig()
        val app = getFederationConfig()
        val fromSession = sessionTenantConfig
            ?.getPropertyAsString(OidfConfigKeys.Federation.ROOT_IDENTIFIER, null)
            ?.takeIf { it.isNotEmpty() }
        val fromCatalog = getTenantConfig(tenantId)?.rootIdentifier
        val root = fromSession ?: fromCatalog ?: return app
        return app.copy(rootIdentifier = root)
    }

    /**
     * Effective KMS config for [tenantId].
     * Precedence: session bare `oidf.kms.default.provider` → `oidf.tenant.<id>.kms.provider` → APP.
     */
    fun getEffectiveKmsConfig(
        tenantId: String?,
        sessionTenantConfig: ConfigService? = null,
    ): KmsConfig {
        if (tenantId.isNullOrBlank()) return getKmsConfig()
        val app = getKmsConfig()
        val fromSession = sessionTenantConfig
            ?.getPropertyAsString(OidfConfigKeys.Kms.DEFAULT_PROVIDER, null)
            ?.takeIf { it.isNotEmpty() }
        val fromCatalog = getTenantConfig(tenantId)?.kmsProvider
        val provider = fromSession ?: fromCatalog ?: return app
        return app.copy(defaultProvider = provider)
    }

    /**
     * Effective identity config for [tenantId].
     * **mode**, session alignment, and external root owner stay APP-fixed.
     * Header claim + allow-list may be overridden per tenant.
     */
    fun getEffectiveIdentityConfig(
        tenantId: String?,
        sessionTenantConfig: ConfigService? = null,
    ): IdentityConfig {
        val app = getIdentityConfig()
        if (tenantId.isNullOrBlank()) return app
        val tenant = getTenantConfig(tenantId)

        val claimFromSession = sessionTenantConfig
            ?.getPropertyAsString(OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val principalsFromSession = sessionTenantConfig
            ?.getPropertyAsString(OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS, null)
            ?.let { parsePrincipalList(it) }

        val claim = claimFromSession
            ?: tenant?.accountHeaderPrincipalClaim
            ?: app.accountHeaderPrincipalClaim
        val principals = principalsFromSession
            ?: tenant?.accountHeaderAllowedPrincipals
            ?: app.accountHeaderAllowedPrincipals

        if (claim == app.accountHeaderPrincipalClaim && principals == app.accountHeaderAllowedPrincipals) {
            return app
        }
        return app.copy(
            accountHeaderPrincipalClaim = claim,
            accountHeaderAllowedPrincipals = principals,
        )
    }

    /**
     * Effective cache locality enum name for [appKey] (e.g. [OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY]).
     * Empty string = use code defaults.
     */
    fun getEffectiveCacheLocality(
        appKey: String,
        tenantId: String?,
        sessionTenantConfig: ConfigService? = null,
    ): String {
        val fromSession = sessionTenantConfig
            ?.getPropertyAsString(appKey, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromSession != null) return fromSession

        if (!tenantId.isNullOrBlank()) {
            val tenant = getTenantConfig(tenantId)
            val fromCatalog = when (appKey) {
                OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY -> tenant?.cacheHttpResolverLocality
                OidfConfigKeys.Cache.TRUST_CHAIN_LOCALITY -> tenant?.cacheTrustChainLocality
                OidfConfigKeys.Cache.ENTITY_CONFIG_LOCALITY -> tenant?.cacheEntityConfigLocality
                OidfConfigKeys.Cache.TRUST_MARK_LOCALITY -> tenant?.cacheTrustMarkLocality
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
            if (fromCatalog != null) return fromCatalog
        }

        return getProperty(appKey, "").trim()
    }

    // ========================================================================
    // Raw Property Access (APP pipeline)
    // ========================================================================

    fun getProperty(key: String, default: String = ""): String

    fun getBooleanProperty(key: String, default: Boolean = false): Boolean

    fun getIntProperty(key: String, default: Int = 0): Int

    fun getLongProperty(key: String, default: Long = 0L): Long

    fun getListProperty(key: String, default: List<String> = emptyList()): List<String>

    enum class ServerType {
        ADMIN,
        FEDERATION,
    }

    companion object {
        fun parsePrincipalList(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

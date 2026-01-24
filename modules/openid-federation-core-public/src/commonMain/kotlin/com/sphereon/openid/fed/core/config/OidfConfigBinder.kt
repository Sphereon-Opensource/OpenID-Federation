package com.sphereon.openid.fed.core.config

/**
 * Configuration binder interface for OpenID Federation.
 *
 * This interface provides typed access to configuration values following
 * IDK's configuration patterns with support for scoped configuration
 * (APP, TENANT, PRINCIPAL).
 *
 * Implementations should use IDK's ConfigService for property resolution
 * with proper fallback handling.
 */
interface OidfConfigBinder {

    // ========================================================================
    // APP Scope Configuration
    // ========================================================================

    /**
     * Get federation core configuration (APP scope).
     */
    fun getFederationConfig(): FederationConfig

    /**
     * Get server configuration for the specified server type.
     */
    fun getServerConfig(type: ServerType): ServerConfig

    /**
     * Get CORS configuration (APP scope).
     */
    fun getCorsConfig(): CorsConfig

    /**
     * Get logger configuration (APP scope).
     */
    fun getLoggerConfig(): LoggerConfig

    /**
     * Get datasource configuration (APP scope).
     */
    fun getDatasourceConfig(): DatasourceConfig

    /**
     * Get OAuth2 configuration (APP scope).
     */
    fun getOAuth2Config(): OAuth2Config

    /**
     * Get KMS configuration (APP scope).
     */
    fun getKmsConfig(): KmsConfig

    /**
     * Get the complete application configuration aggregate.
     */
    fun getAppConfig(): OidfAppConfig

    // ========================================================================
    // TENANT Scope Configuration
    // ========================================================================

    /**
     * Get tenant-specific configuration (TENANT scope).
     * Returns null if no tenant-specific overrides exist.
     *
     * @param tenantId The tenant identifier
     */
    fun getTenantConfig(tenantId: String): TenantConfig?

    /**
     * Get effective federation config for a tenant.
     * Falls back to APP scope if no tenant override exists.
     *
     * @param tenantId The tenant identifier
     */
    fun getEffectiveFederationConfig(tenantId: String?): FederationConfig {
        if (tenantId == null) return getFederationConfig()

        val tenantConfig = getTenantConfig(tenantId)
        return if (tenantConfig?.rootIdentifier != null) {
            getFederationConfig().copy(rootIdentifier = tenantConfig.rootIdentifier)
        } else {
            getFederationConfig()
        }
    }

    // ========================================================================
    // Raw Property Access
    // ========================================================================

    /**
     * Get a raw string property value.
     *
     * @param key The property key
     * @param default Default value if property not found
     */
    fun getProperty(key: String, default: String = ""): String

    /**
     * Get a boolean property value.
     *
     * @param key The property key
     * @param default Default value if property not found
     */
    fun getBooleanProperty(key: String, default: Boolean = false): Boolean

    /**
     * Get an integer property value.
     *
     * @param key The property key
     * @param default Default value if property not found
     */
    fun getIntProperty(key: String, default: Int = 0): Int

    /**
     * Get a long property value.
     *
     * @param key The property key
     * @param default Default value if property not found
     */
    fun getLongProperty(key: String, default: Long = 0L): Long

    /**
     * Get a list property value (comma-separated string).
     *
     * @param key The property key
     * @param default Default value if property not found
     */
    fun getListProperty(key: String, default: List<String> = emptyList()): List<String>

    /**
     * Server types for configuration retrieval.
     */
    enum class ServerType {
        /** Admin server (port 8081 by default) */
        ADMIN,
        /** Federation protocol server (port 8080 by default) */
        FEDERATION
    }
}

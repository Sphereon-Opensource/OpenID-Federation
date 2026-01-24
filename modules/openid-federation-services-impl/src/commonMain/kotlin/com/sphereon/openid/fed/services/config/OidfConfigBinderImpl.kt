package com.sphereon.openid.fed.services.config

import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.common.config.normalizeKeyForEnv
import com.sphereon.openid.fed.core.config.*
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of OidfConfigBinder that provides typed configuration access.
 *
 * ## Configuration Sources (in order of precedence):
 *
 * 1. **IDK-normalized environment variables** (e.g., `OIDF_FEDERATION_ROOT_IDENTIFIER`)
 *    - Property key `oidf.federation.root.identifier` becomes env var `OIDF_FEDERATION_ROOT_IDENTIFIER`
 *
 * 2. **Legacy environment variables** (JVM only, for backwards compatibility)
 *    - Existing deployments using `ROOT_IDENTIFIER`, `DATASOURCE_URL`, etc. continue to work
 *    - Mapped via platform-specific [getEnvironmentVariable] implementation
 *    - JS/Native platforms only support IDK-normalized env vars
 *
 * 3. **Hardcoded defaults** in this class
 *
 * ## Platform Support
 *
 * This implementation is multiplatform (JVM, JS, Native). Legacy environment variable
 * support is only available on JVM - other platforms use IDK-normalized env vars only.
 *
 * ## Note on reference.conf
 *
 * While a `reference.conf` file exists in the common module with the same defaults,
 * HOCON file loading is NOT implemented. The reference.conf serves as documentation
 * of available configuration options. All actual config comes from environment variables.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = OidfConfigBinder::class)
class OidfConfigBinderImpl : OidfConfigBinder {

    override fun getFederationConfig(): FederationConfig {
        return FederationConfig(
            rootIdentifier = getProperty(
                OidfConfigKeys.Federation.ROOT_IDENTIFIER,
                "http://localhost:8080"
            ),
            devMode = getBooleanProperty(
                OidfConfigKeys.Federation.DEV_MODE,
                false
            )
        )
    }

    override fun getServerConfig(type: OidfConfigBinder.ServerType): ServerConfig {
        return when (type) {
            OidfConfigBinder.ServerType.ADMIN -> ServerConfig(
                port = getIntProperty(OidfConfigKeys.Server.Admin.PORT, 8081),
                host = getProperty(OidfConfigKeys.Server.Admin.HOST, "0.0.0.0")
            )
            OidfConfigBinder.ServerType.FEDERATION -> ServerConfig(
                port = getIntProperty(OidfConfigKeys.Server.Federation.PORT, 8080),
                host = getProperty(OidfConfigKeys.Server.Federation.HOST, "0.0.0.0")
            )
        }
    }

    override fun getCorsConfig(): CorsConfig {
        return CorsConfig(
            allowedOrigins = getListProperty(
                OidfConfigKeys.Cors.ALLOWED_ORIGINS,
                listOf("*")
            ),
            allowedMethods = getListProperty(
                OidfConfigKeys.Cors.ALLOWED_METHODS,
                listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            ),
            allowedHeaders = getListProperty(
                OidfConfigKeys.Cors.ALLOWED_HEADERS,
                listOf("*")
            ),
            maxAge = getLongProperty(OidfConfigKeys.Cors.MAX_AGE, 3600)
        )
    }

    override fun getLoggerConfig(): LoggerConfig {
        return LoggerConfig(
            severity = getProperty(OidfConfigKeys.Logger.SEVERITY, "INFO"),
            output = getProperty(OidfConfigKeys.Logger.OUTPUT, "TEXT"),
            includeTimestamp = getBooleanProperty(OidfConfigKeys.Logger.INCLUDE_TIMESTAMP, false)
        )
    }

    override fun getDatasourceConfig(): DatasourceConfig {
        return DatasourceConfig(
            url = getProperty(OidfConfigKeys.Datasource.URL, ""),
            user = getProperty(OidfConfigKeys.Datasource.USER, ""),
            password = getProperty(OidfConfigKeys.Datasource.PASSWORD, ""),
            db = getProperty(OidfConfigKeys.Datasource.DB, "")
        )
    }

    override fun getOAuth2Config(): OAuth2Config {
        return OAuth2Config(
            issuerUri = getProperty(OidfConfigKeys.OAuth2.ISSUER_URI, "")
        )
    }

    override fun getKmsConfig(): KmsConfig {
        return KmsConfig(
            defaultProvider = getProperty(OidfConfigKeys.Kms.DEFAULT_PROVIDER, "memory")
        )
    }

    override fun getAppConfig(): OidfAppConfig {
        return OidfAppConfig(
            federation = getFederationConfig(),
            adminServer = getServerConfig(OidfConfigBinder.ServerType.ADMIN),
            federationServer = getServerConfig(OidfConfigBinder.ServerType.FEDERATION),
            cors = getCorsConfig(),
            logger = getLoggerConfig(),
            datasource = getDatasourceConfig(),
            oauth2 = getOAuth2Config(),
            kms = getKmsConfig()
        )
    }

    override fun getTenantConfig(tenantId: String): TenantConfig? {
        val rootIdentifier = getPropertyOrNull(OidfConfigKeys.Tenant.rootIdentifier(tenantId))
        val kmsProvider = getPropertyOrNull(OidfConfigKeys.Tenant.kmsProvider(tenantId))

        // Only return TenantConfig if at least one override is set
        return if (rootIdentifier != null || kmsProvider != null) {
            TenantConfig(
                tenantId = tenantId,
                rootIdentifier = rootIdentifier,
                kmsProvider = kmsProvider
            )
        } else {
            null
        }
    }

    // ========================================================================
    // Raw Property Access
    // ========================================================================

    override fun getProperty(key: String, default: String): String {
        return getEnvironmentVariable(key) ?: default
    }

    override fun getBooleanProperty(key: String, default: Boolean): Boolean {
        val value = getProperty(key, "")
        if (value.isEmpty()) return default
        return value.lowercase() in listOf("true", "1", "yes", "on")
    }

    override fun getIntProperty(key: String, default: Int): Int {
        val value = getProperty(key, "")
        if (value.isEmpty()) return default
        return value.toIntOrNull() ?: default
    }

    override fun getLongProperty(key: String, default: Long): Long {
        val value = getProperty(key, "")
        if (value.isEmpty()) return default
        return value.toLongOrNull() ?: default
    }

    override fun getListProperty(key: String, default: List<String>): List<String> {
        val value = getProperty(key, "")
        if (value.isEmpty()) return default
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun getPropertyOrNull(key: String): String? {
        val value = getProperty(key, "")
        return if (value.isEmpty()) null else value
    }
}

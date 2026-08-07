package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.core.config.CorsConfig
import com.sphereon.openid.fed.core.config.DatasourceConfig
import com.sphereon.openid.fed.core.config.FederationConfig
import com.sphereon.openid.fed.core.config.KmsConfig
import com.sphereon.openid.fed.core.config.LoggerConfig
import com.sphereon.openid.fed.core.config.OAuth2Config
import com.sphereon.openid.fed.core.config.OidfAppConfig
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfPropertyResolution
import com.sphereon.openid.fed.core.config.ServerConfig
import com.sphereon.openid.fed.core.config.TenantConfig
import com.sphereon.openid.fed.core.tenant.IdentityConfig
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.SessionAlignment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Typed OIDF configuration binder.
 *
 * ## Resolution precedence (via [OidfPropertyResolution])
 *
 * 1. IDK [AppConfigService] (full property pipeline when the app graph provides it)
 * 2. [com.sphereon.core.api.conf.DefaultAppMapPropertySource] (explicit / KMS bootstrap)
 * 3. Environment variables (IDK-normalized + legacy aliases)
 * 4. [com.sphereon.openid.fed.core.config.OidfFilePropertySource] (reference/application files)
 * 5. [com.sphereon.openid.fed.core.config.OidfConfigDefaults]
 *
 * Call [com.sphereon.openid.fed.core.config.OidfConfigBootstrap.seed] at server start so
 * file defaults and software KMS are loaded before AppGraph creation.
 *
 * Sensitive values: prefer `*.secret.id` handles + [com.sphereon.core.api.conf.OpaqueSecretResolver]
 * at suspend use sites; this binder remains synchronous for DI-friendly access.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<OidfConfigBinder>())
class OidfConfigBinderImpl(
    private val appConfigService: AppConfigService,
) : OidfConfigBinder {

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
            issuerUri = getProperty(OidfConfigKeys.OAuth2.ISSUER_URI, ""),
            audience = getProperty(OidfConfigKeys.OAuth2.AUDIENCE, ""),
            jwtAuthEnabled = getProperty(OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED, "auto"),
        )
    }

    override fun getKmsConfig(): KmsConfig {
        return KmsConfig(
            defaultProvider = getProperty(OidfConfigKeys.Kms.DEFAULT_PROVIDER, "memory")
        )
    }

    override fun getIdentityConfig(): IdentityConfig {
        val mode = IdentityMode.parse(getPropertyOrNull(OidfConfigKeys.Identity.MODE))
        val rootTenant = getPropertyOrNull(OidfConfigKeys.Identity.PLATFORM_ROOT_TENANT_ID)
        val allowAnonymous = getBooleanProperty(OidfConfigKeys.Identity.ALLOW_ANONYMOUS_ADMIN, false)
        val sessionAlignment = SessionAlignment.parse(
            getPropertyOrNull(OidfConfigKeys.Identity.SESSION_ALIGNMENT),
        )
        val sessionFixed = getProperty(
            OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID,
            "default",
        )
        return IdentityConfig(
            mode = mode,
            platformRootTenantId = rootTenant,
            allowAnonymousAdmin = allowAnonymous,
            sessionAlignment = sessionAlignment,
            sessionFixedTenantId = sessionFixed.ifBlank { "default" },
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
            kms = getKmsConfig(),
            identity = getIdentityConfig()
        )
    }

    override fun getTenantConfig(tenantId: String): TenantConfig? {
        val rootIdentifier = getPropertyOrNull(OidfConfigKeys.Tenant.rootIdentifier(tenantId))
        val kmsProvider = getPropertyOrNull(OidfConfigKeys.Tenant.kmsProvider(tenantId))

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

    override fun getProperty(key: String, default: String): String {
        return OidfPropertyResolution.resolveString(
            key = key,
            default = default,
            envLookup = { getEnvironmentVariable(it) },
            appConfig = appConfigService,
        )
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

    private fun getPropertyOrNull(key: String): String? {
        val value = getProperty(key, "")
        return if (value.isEmpty()) null else value
    }
}

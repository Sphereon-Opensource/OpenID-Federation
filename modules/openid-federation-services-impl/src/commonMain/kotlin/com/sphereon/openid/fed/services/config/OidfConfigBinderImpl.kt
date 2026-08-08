package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.openid.fed.core.config.CorsConfig
import com.sphereon.openid.fed.core.config.DatasourceConfig
import com.sphereon.openid.fed.core.config.FederationClientAuthMembershipPolicy
import com.sphereon.openid.fed.core.config.FederationConfig
import com.sphereon.openid.fed.core.config.FederationEndpointAuthMethods
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
import com.sphereon.openid.fed.core.tenant.IdentityModeDefaults
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.SessionAlignment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * OIDF configuration binder (DI: [AppScope]).
 *
 * APP keys resolve via [OidfPropertyResolution] + [AppConfigService].
 * Tenant-overridable settings use [getTenantConfig] / [getEffective*] (catalog
 * `oidf.tenant.<id>.*` + optional session [com.sphereon.core.api.conf.ConfigService]).
 *
 * Process-fixed: ports, datasource, identity.mode, OAuth2 issuer, CORS, logger.
 * Tenant-overridable: root entity URL, KMS provider, cache localities, header allow-list.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<OidfConfigBinder>())
class OidfConfigBinderImpl(
    private val appConfigService: AppConfigService,
) : OidfConfigBinder {

    override fun getFederationConfig(): FederationConfig {
        val defaultAuthMethods = getListProperty(
            OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_DEFAULT,
            listOf("none")
        )
        fun authMethods(key: String): List<String> {
            val raw = getProperty(key, "")
            return if (raw.isBlank()) defaultAuthMethods else getListProperty(key, defaultAuthMethods)
        }
        return FederationConfig(
            rootIdentifier = getProperty(
                OidfConfigKeys.Federation.ROOT_IDENTIFIER,
                "http://localhost:8080"
            ),
            devMode = getBooleanProperty(
                OidfConfigKeys.Federation.DEV_MODE,
                false
            ),
            endpointAuthMethods = FederationEndpointAuthMethods(
                fetch = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_FETCH),
                list = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_LIST),
                resolve = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_RESOLVE),
                trustMarkStatus = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_TRUST_MARK_STATUS),
                trustMarkList = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_TRUST_MARK_LIST),
                trustMark = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_TRUST_MARK),
                historicalKeys = authMethods(OidfConfigKeys.Federation.ENDPOINT_AUTH_METHODS_HISTORICAL_KEYS),
            ),
            endpointAuthSigningAlgs = getListProperty(
                OidfConfigKeys.Federation.ENDPOINT_AUTH_SIGNING_ALGS,
                listOf("RS256", "ES256", "PS256")
            ),
            endpointAuthMembershipPolicy = FederationClientAuthMembershipPolicy.fromConfig(
                getProperty(
                    OidfConfigKeys.Federation.ENDPOINT_AUTH_MEMBERSHIP_POLICY,
                    "hybrid",
                )
            ),
            endpointAuthTrustAnchors = getListProperty(
                OidfConfigKeys.Federation.ENDPOINT_AUTH_TRUST_ANCHORS,
                emptyList(),
            ),
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
        // Unset mode → auto: upgrade/account-classpath → ACCOUNT; greenfield host → EXTERNAL
        val mode = IdentityModeDefaults.resolve(
            explicitMode = getPropertyOrNull(OidfConfigKeys.Identity.MODE),
        )
        val rootTenant =
            getPropertyOrNull(OidfConfigKeys.Identity.EXTERNAL_ROOT_TENANT_ID)
                ?: getPropertyOrNull(OidfConfigKeys.Identity.PLATFORM_ROOT_TENANT_ID)
        val sessionAlignment = SessionAlignment.parse(
            getPropertyOrNull(OidfConfigKeys.Identity.SESSION_ALIGNMENT),
        )
        val sessionFixed = getProperty(
            OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID,
            "default",
        )
        val headerClaim =
            getProperty(OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM, "sub")
                .trim()
                .ifEmpty { "sub" }
        // Default empty = deny header rebind (must configure operators explicitly).
        // Do not treat blank as wildcard — only an explicit "*" entry enables any-authn.
        val allowedRaw =
            getProperty(OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS, "")
        val allowedPrincipals =
            allowedRaw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return IdentityConfig(
            mode = mode,
            externalRootTenantId = rootTenant,
            sessionAlignment = sessionAlignment,
            sessionFixedTenantId = sessionFixed.ifBlank { "default" },
            accountHeaderPrincipalClaim = headerClaim,
            accountHeaderAllowedPrincipals = allowedPrincipals,
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
        if (tenantId.isBlank()) return null
        val principalsRaw =
            getPropertyOrNull(OidfConfigKeys.Tenant.accountHeaderAllowedPrincipals(tenantId))
        val config = TenantConfig(
            tenantId = tenantId,
            rootIdentifier = getPropertyOrNull(OidfConfigKeys.Tenant.rootIdentifier(tenantId)),
            kmsProvider = getPropertyOrNull(OidfConfigKeys.Tenant.kmsProvider(tenantId)),
            cacheHttpResolverLocality =
                getPropertyOrNull(OidfConfigKeys.Tenant.cacheHttpResolverLocality(tenantId)),
            cacheTrustChainLocality =
                getPropertyOrNull(OidfConfigKeys.Tenant.cacheTrustChainLocality(tenantId)),
            cacheEntityConfigLocality =
                getPropertyOrNull(OidfConfigKeys.Tenant.cacheEntityConfigLocality(tenantId)),
            cacheTrustMarkLocality =
                getPropertyOrNull(OidfConfigKeys.Tenant.cacheTrustMarkLocality(tenantId)),
            accountHeaderPrincipalClaim =
                getPropertyOrNull(OidfConfigKeys.Tenant.accountHeaderPrincipalClaim(tenantId)),
            accountHeaderAllowedPrincipals = principalsRaw?.let {
                OidfConfigBinder.parsePrincipalList(it)
            },
        )
        return if (config.hasAnyOverride()) config else null
    }

    override fun getProperty(key: String, default: String): String {
        return OidfPropertyResolution.resolveString(
            key = key,
            default = default,
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

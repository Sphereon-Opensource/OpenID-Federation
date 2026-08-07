package com.sphereon.openid.fed.services.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.common.config.OidfEnvOverrides
import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfConfigSources
import com.sphereon.openid.fed.core.config.OidfFilePropertySource
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.SessionAlignment
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typed [OidfConfigBinder] regression: environment tier installed via [OidfConfigSources],
 * empty AppConfig so values come from the config-system environment source
 * (same [getEnvironmentVariable] path as production install).
 */
class OidfConfigBinderEnvRegressionTest {

    private lateinit var binder: OidfConfigBinderImpl

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
        clearOidfAppMap()
        // Config-system env tier (not a per-call envLookup on the binder)
        OidfConfigSources.installEnvironment { getEnvironmentVariable(it) }
        val appConfig = mockk<AppConfigService>(relaxed = true)
        every { appConfig.getPropertyAsString(any(), any()) } returns null
        binder = OidfConfigBinderImpl(appConfig)
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
        clearOidfAppMap()
    }

    private fun clearOidfAppMap() {
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.") }
            .toList()
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    @Test
    fun legacy_env_example_profile_binds_typed_sections() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "ROOT_IDENTIFIER" to "http://localhost:8080",
                "DATASOURCE_URL" to "jdbc:postgresql://db:5432/openid-federation-db",
                "DATASOURCE_USER" to "openid-federation-db-user",
                "DATASOURCE_PASSWORD" to "openid-federation-db-password",
                "DATASOURCE_DB" to "openid-federation-db",
                "OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" to "http://keycloak:8080/realms/openid-federation",
                "DEV_MODE" to "true",
                "LOGGER_SEVERITY" to "Verbose",
                "LOGGER_OUTPUT" to "JSON",
                "CORS_ALLOWED_ORIGINS" to "https://a.example,https://b.example",
                "CORS_ALLOWED_METHODS" to "GET,POST",
                "CORS_ALLOWED_HEADERS" to "Authorization,Content-Type",
                "CORS_MAX_AGE" to "7200",
                "KMS_PROVIDER" to "memory",
                "ADMIN_SERVER_PORT" to "9081",
                "SERVER_PORT" to "9080",
                "IDENTITY_MODE" to "account",
            ),
        ) {
            val fed = binder.getFederationConfig()
            assertEquals("http://localhost:8080", fed.rootIdentifier)
            assertEquals(true, fed.devMode)

            val ds = binder.getDatasourceConfig()
            assertEquals("jdbc:postgresql://db:5432/openid-federation-db", ds.url)
            assertEquals("openid-federation-db-user", ds.user)
            assertEquals("openid-federation-db-password", ds.password)
            assertEquals("openid-federation-db", ds.db)

            val oauth = binder.getOAuth2Config()
            assertEquals("http://keycloak:8080/realms/openid-federation", oauth.issuerUri)

            val logger = binder.getLoggerConfig()
            assertEquals("Verbose", logger.severity)
            assertEquals("JSON", logger.output)

            val cors = binder.getCorsConfig()
            assertEquals(listOf("https://a.example", "https://b.example"), cors.allowedOrigins)
            assertEquals(listOf("GET", "POST"), cors.allowedMethods)
            assertEquals(listOf("Authorization", "Content-Type"), cors.allowedHeaders)
            assertEquals(7200L, cors.maxAge)

            assertEquals("memory", binder.getKmsConfig().defaultProvider)
            assertEquals(9081, binder.getServerConfig(OidfConfigBinder.ServerType.ADMIN).port)
            assertEquals(9080, binder.getServerConfig(OidfConfigBinder.ServerType.FEDERATION).port)
            assertEquals(IdentityMode.ACCOUNT, binder.getIdentityConfig().mode)
        }
    }

    @Test
    fun idk_normalized_env_binds_identity_and_oauth2() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_IDENTITY_MODE" to "external",
                "OIDF_EXTERNAL_ROOT_TENANT_ID" to "root-tenant",
                "OIDF_SESSION_ALIGNMENT" to "fixed",
                "OIDF_SESSION_FIXED_TENANT_ID" to "fixed-t",
                "OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM" to "preferred_username",
                "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS" to "ops,service",
                "OIDF_OAUTH2_ISSUER_URI" to "https://as.example/issuer",
                "OIDF_OAUTH2_AUDIENCE" to "openid-federation-admin",
                "OIDF_OAUTH2_JWT_AUTH_ENABLED" to "true",
            ),
        ) {
            val identity = binder.getIdentityConfig()
            assertEquals(IdentityMode.EXTERNAL, identity.mode)
            assertEquals("root-tenant", identity.externalRootTenantId)
            assertEquals(SessionAlignment.FIXED, identity.sessionAlignment)
            assertEquals("fixed-t", identity.sessionFixedTenantId)
            assertEquals("preferred_username", identity.accountHeaderPrincipalClaim)
            assertEquals(listOf("ops", "service"), identity.accountHeaderAllowedPrincipals)

            val oauth = binder.getOAuth2Config()
            assertEquals("https://as.example/issuer", oauth.issuerUri)
            assertEquals("openid-federation-admin", oauth.audience)
            assertEquals("true", oauth.jwtAuthEnabled)
        }
    }

    @Test
    fun platform_alias_identity_mode_and_root_tenant() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_IDENTITY_MODE" to "platform",
                "OIDF_PLATFORM_ROOT_TENANT_ID" to "plat-root",
            ),
        ) {
            val identity = binder.getIdentityConfig()
            assertEquals(IdentityMode.EXTERNAL, identity.mode)
            assertEquals("plat-root", identity.externalRootTenantId)
        }
    }

    @Test
    fun app_dev_mode_alias_enables_dev_mode() {
        OidfEnvOverrides.withEnv(mapOf("APP_DEV_MODE" to "true")) {
            assertEquals(true, binder.getFederationConfig().devMode)
        }
    }

    @Test
    fun getAppConfig_aggregate_reads_full_env_profile() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_FEDERATION_ROOT_IDENTIFIER" to "https://agg.example",
                "OIDF_SERVER_ADMIN_PORT" to "18081",
                "OIDF_SERVER_FEDERATION_PORT" to "18080",
                "OIDF_KMS_DEFAULT_PROVIDER" to "azure",
                "OIDF_IDENTITY_MODE" to "legacy",
            ),
        ) {
            val app = binder.getAppConfig()
            assertEquals("https://agg.example", app.federation.rootIdentifier)
            assertEquals(18081, app.adminServer.port)
            assertEquals(18080, app.federationServer.port)
            assertEquals("azure", app.kms.defaultProvider)
            assertEquals(IdentityMode.ACCOUNT, app.identity.mode)
        }
    }

    @Test
    fun empty_allow_list_denies_header_rebind_by_default() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_IDENTITY_MODE" to "account",
                "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS" to "",
            ),
        ) {
            assertTrue(binder.getIdentityConfig().accountHeaderAllowedPrincipals.isEmpty())
        }
    }

    @Test
    fun binder_property_helpers_match_env_lookup() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_SERVER_ADMIN_PORT" to "7777",
                "OIDF_FEDERATION_DEV_MODE" to "yes",
                "OIDF_CORS_MAX_AGE" to "99",
                "OIDF_CORS_ALLOWED_METHODS" to "GET, OPTIONS ,POST",
            ),
        ) {
            assertEquals(7777, binder.getIntProperty(OidfConfigKeys.Server.Admin.PORT, 0))
            assertEquals(true, binder.getBooleanProperty(OidfConfigKeys.Federation.DEV_MODE, false))
            assertEquals(99L, binder.getLongProperty(OidfConfigKeys.Cors.MAX_AGE, 0))
            assertEquals(
                listOf("GET", "OPTIONS", "POST"),
                binder.getListProperty(OidfConfigKeys.Cors.ALLOWED_METHODS),
            )
            assertEquals(
                "7777",
                getEnvironmentVariable(OidfConfigKeys.Server.Admin.PORT),
            )
        }
    }
}

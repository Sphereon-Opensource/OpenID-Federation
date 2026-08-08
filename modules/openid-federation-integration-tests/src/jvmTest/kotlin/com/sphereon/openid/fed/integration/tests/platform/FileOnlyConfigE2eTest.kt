package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.common.config.OidfConfigEnvironment
import com.sphereon.openid.fed.common.config.OidfEnvOverrides
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfConfigSources
import com.sphereon.openid.fed.core.config.OidfFilePropertySource
import com.sphereon.openid.fed.core.config.OidfPropertyResolution
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
import com.sphereon.openid.fed.server.federation.ktor.di.createFederationServerAppGraph
import com.sphereon.openid.fed.services.config.OidfConfigBinderImpl
import com.sphereon.openid.fed.services.config.resolveEffectiveKmsProviderId
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zero process-env config smoke using flat property keys in the OIDFed file tier.
 *
 * Nested `application.yaml` is produced by IDK **lib-conf-yaml** (APP/tenant/principal)
 * when the AppGraph registers PropertySourceContribution — not by OIDFed parsers.
 *
 * Docker: `docker compose -f docker-compose.file-only.yaml up` mounts
 * `config/application.file-only.yaml` → `/app/config/application.yaml` for IDK.
 */
class FileOnlyConfigE2eTest {

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        OidfFilePropertySource.clear()
        clearOidfAppMap()
        OidfConfigSources.installEnvironment { null }
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
            .filter { it.startsWith("oidf.") || it.startsWith("kms.") }
            .toList()
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    @Test
    fun file_only_properties_drive_binder_and_effective_kms_without_env() {
        OidfFilePropertySource.putAll(
            mapOf(
                OidfConfigKeys.Federation.ROOT_IDENTIFIER to "https://file-only-e2e.example",
                OidfConfigKeys.Datasource.URL to "jdbc:postgresql://localhost:5432/openid-federation-db",
                OidfConfigKeys.Datasource.USER to "openid-federation-db-user",
                OidfConfigKeys.Datasource.PASSWORD to "openid-federation-db-password",
                OidfConfigKeys.OAuth2.ISSUER_URI to "http://127.0.0.1:9999/realms/file-only",
                OidfConfigKeys.Identity.MODE to "account",
                OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
                OidfConfigKeys.Tenant.kmsProvider("tenant-a") to "azure",
                OidfConfigKeys.Tenant.rootIdentifier("tenant-a") to "https://tenant-a.file-only.example",
            ),
        )

        OidfEnvOverrides.withEnv(emptyMap()) {
            assertEquals(
                "https://file-only-e2e.example",
                OidfPropertyResolution.resolveString(OidfConfigKeys.Federation.ROOT_IDENTIFIER),
            )

            val appConfig = mockk<com.sphereon.core.api.conf.AppConfigService>(relaxed = true)
            every { appConfig.getPropertyAsString(any(), any()) } returns null
            val binder = OidfConfigBinderImpl(appConfig)

            assertEquals("memory", binder.getKmsConfig().defaultProvider)
            assertEquals("azure", binder.resolveEffectiveKmsProviderId("tenant-a"))
            assertEquals(
                "https://tenant-a.file-only.example",
                binder.getEffectiveFederationConfig("tenant-a").rootIdentifier,
            )
            assertTrue(binder.getOAuth2Config().issuerUri.isNotBlank())
        }
    }

    @Test
    fun file_only_bootstrap_and_app_graphs_start_without_process_env() {
        OidfConfigEnvironment.install()
        OidfConfigSources.installEnvironment { null }

        // AppGraph AppConfigService may also see classpath application.yaml (IDK lib-conf-yaml).
        // This test only proves graphs boot with **no process env** — not that file tier beats AppConfig.
        OidfEnvOverrides.withEnv(emptyMap()) {
            OidfConfigBootstrap.seed(
                appId = "file-only-admin",
                profile = "default",
                loadFileDefaults = true,
                seedSoftwareKms = true,
            )
            val admin = createAdminServerAppGraph(
                application = this,
                appId = "file-only-admin",
                profile = "default",
            )
            assertTrue(admin.serverConfig.port > 0, "admin graph must expose a bind port")
            assertTrue(
                admin.serverConfig.rootIdentifier.isNotBlank(),
                "admin graph must resolve federation root identifier without process env",
            )

            OidfConfigBootstrap.seed(
                appId = "file-only-fed",
                profile = "default",
                loadFileDefaults = true,
                seedSoftwareKms = true,
            )
            val fed = createFederationServerAppGraph(
                application = this,
                appId = "file-only-fed",
                profile = "default",
            )
            assertTrue(fed.serverConfig.port > 0, "federation graph must expose a bind port")
        }
    }
}

package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke: config from **flat properties / file tier only** (no process env).
 *
 * Nested `application.yaml` is loaded by **IDK lib-conf-yaml** into AppConfigService,
 * not by OIDFed. This test covers the OIDFed reference/file tier + binder-facing keys.
 */
class OidfFileOnlyConfigSmokeTest {

    @BeforeTest
    fun setUp() {
        OidfConfigBootstrap.resetForTests()
        OidfConfigSources.installEnvironment { null }
        clearOidfAppMap()
    }

    @AfterTest
    fun tearDown() {
        OidfConfigBootstrap.resetForTests()
        clearOidfAppMap()
    }

    private fun clearOidfAppMap() {
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.") }
            .toList()
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    @Test
    fun properties_file_tier_drives_core_keys_without_env() {
        OidfFilePropertySource.putAll(
            mapOf(
                OidfConfigKeys.Federation.ROOT_IDENTIFIER to "https://file-only.example",
                OidfConfigKeys.Federation.DEV_MODE to "true",
                OidfConfigKeys.Server.Admin.PORT to "19081",
                OidfConfigKeys.Server.Federation.PORT to "19080",
                OidfConfigKeys.Datasource.URL to "jdbc:postgresql://file-db/oidf",
                OidfConfigKeys.Datasource.USER to "file-user",
                OidfConfigKeys.Datasource.PASSWORD to "file-pass",
                OidfConfigKeys.OAuth2.ISSUER_URI to "https://as.file-only/issuer",
                OidfConfigKeys.Identity.MODE to "external",
                OidfConfigKeys.Identity.EXTERNAL_ROOT_TENANT_ID to "file-root-tenant",
                OidfConfigKeys.Kms.DEFAULT_PROVIDER to "azure",
                OidfConfigKeys.Cors.ALLOWED_ORIGINS to "https://app.file-only",
                OidfConfigKeys.Logger.SEVERITY to "WARN",
                OidfConfigKeys.Logger.OUTPUT to "JSON",
            ),
        )

        assertEquals(
            "https://file-only.example",
            OidfPropertyResolution.resolveString(OidfConfigKeys.Federation.ROOT_IDENTIFIER),
        )
        assertEquals("true", OidfPropertyResolution.resolveString(OidfConfigKeys.Federation.DEV_MODE))
        assertEquals("19081", OidfPropertyResolution.resolveString(OidfConfigKeys.Server.Admin.PORT))
        assertEquals(
            "jdbc:postgresql://file-db/oidf",
            OidfPropertyResolution.resolveString(OidfConfigKeys.Datasource.URL),
        )
        assertEquals("external", OidfPropertyResolution.resolveString(OidfConfigKeys.Identity.MODE))
        assertEquals("azure", OidfPropertyResolution.resolveString(OidfConfigKeys.Kms.DEFAULT_PROVIDER))
    }

    @Test
    fun packaged_reference_defaults_load_without_env() {
        OidfConfigSources.installEnvironment { null }
        OidfConfigFileLoader.load(profile = "default", force = true)
        val root = OidfPropertyResolution.resolveString(OidfConfigKeys.Federation.ROOT_IDENTIFIER)
        assertTrue(root.isNotBlank(), "expected reference/file default for root identifier")
        assertEquals(
            "account",
            OidfPropertyResolution.resolveString(OidfConfigKeys.Identity.MODE, default = "account"),
        )
    }
}

package com.sphereon.openid.fed.common.config

import com.sphereon.openid.fed.core.config.OidfConfigKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog integrity + isolation tests for [LegacyEnvMappingPropertySource].
 *
 * These pin the env-var ↔ oidf.* contract so migrations to the IDK config
 * pipeline cannot drop a supported deployment variable.
 */
class LegacyEnvMappingPropertySourceTest {

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
    }

    @Test
    fun catalog_contains_core_deployment_vars_from_env_example() {
        val keys = LegacyEnvMappingPropertySource.legacyMappings.keys
        // .env.example core set
        assertTrue("ROOT_IDENTIFIER" in keys)
        assertTrue("DATASOURCE_URL" in keys)
        assertTrue("DATASOURCE_USER" in keys)
        assertTrue("DATASOURCE_PASSWORD" in keys)
        assertTrue("DATASOURCE_DB" in keys)
        assertTrue("OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" in keys)
        assertTrue("OIDF_OAUTH2_ISSUER_URI" in keys)
        assertTrue("DEV_MODE" in keys)
        assertTrue("LOGGER_SEVERITY" in keys)
        assertTrue("LOGGER_OUTPUT" in keys)
        assertTrue("CORS_ALLOWED_ORIGINS" in keys)
        assertTrue("CORS_ALLOWED_METHODS" in keys)
        assertTrue("CORS_ALLOWED_HEADERS" in keys)
        assertTrue("CORS_MAX_AGE" in keys)
        assertTrue("KMS_PROVIDER" in keys)
        assertTrue("OIDF_IDENTITY_MODE" in keys)
    }

    @Test
    fun catalog_maps_to_expected_oidf_keys() {
        val m = LegacyEnvMappingPropertySource.legacyMappings
        assertEquals(OidfConfigKeys.Federation.ROOT_IDENTIFIER, m["ROOT_IDENTIFIER"])
        assertEquals(OidfConfigKeys.Datasource.URL, m["DATASOURCE_URL"])
        assertEquals(OidfConfigKeys.OAuth2.ISSUER_URI, m["OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI"])
        assertEquals(OidfConfigKeys.OAuth2.ISSUER_URI, m["OIDF_OAUTH2_ISSUER_URI"])
        assertEquals(OidfConfigKeys.Identity.MODE, m["OIDF_IDENTITY_MODE"])
        assertEquals(OidfConfigKeys.Identity.MODE, m["IDENTITY_MODE"])
        assertEquals(OidfConfigKeys.Server.Admin.PORT, m["ADMIN_SERVER_PORT"])
        assertEquals(OidfConfigKeys.Server.Federation.PORT, m["SERVER_PORT"])
        assertEquals(OidfConfigKeys.Kms.DEFAULT_PROVIDER, m["KMS_PROVIDER"])
        assertEquals(OidfConfigKeys.Federation.DEV_MODE, m["APP_DEV_MODE"])
        assertEquals(OidfConfigKeys.Federation.DEV_MODE, m["DEV_MODE"])
    }

    @Test
    fun every_mapping_target_is_non_blank_dot_or_kms_key() {
        LegacyEnvMappingPropertySource.legacyMappings.forEach { (env, idk) ->
            assertTrue(idk.isNotBlank(), "empty idk key for $env")
            assertTrue(
                idk.startsWith("oidf.") || idk.startsWith("kms."),
                "unexpected idk key for $env: $idk",
            )
        }
    }

    @Test
    fun legacyAliasesFor_returns_all_aliases_for_multi_mapped_keys() {
        val oauthAliases = LegacyEnvMappingPropertySource.legacyAliasesFor(OidfConfigKeys.OAuth2.ISSUER_URI)
        assertTrue("OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" in oauthAliases)
        assertTrue("OIDF_OAUTH2_ISSUER_URI" in oauthAliases)

        val devAliases = LegacyEnvMappingPropertySource.legacyAliasesFor(OidfConfigKeys.Federation.DEV_MODE)
        assertTrue("DEV_MODE" in devAliases)
        assertTrue("APP_DEV_MODE" in devAliases)

        val modeAliases = LegacyEnvMappingPropertySource.legacyAliasesFor(OidfConfigKeys.Identity.MODE)
        assertTrue("OIDF_IDENTITY_MODE" in modeAliases)
        assertTrue("IDENTITY_MODE" in modeAliases)
    }

    @Test
    fun getProperty_prefers_normalized_oidf_env_over_legacy() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "OIDF_FEDERATION_ROOT_IDENTIFIER" to "https://normalized.example",
                "ROOT_IDENTIFIER" to "https://legacy.example",
            ),
        ) {
            assertEquals(
                "https://normalized.example",
                LegacyEnvMappingPropertySource.getProperty(OidfConfigKeys.Federation.ROOT_IDENTIFIER),
            )
        }
    }

    @Test
    fun getProperty_falls_back_to_each_legacy_alias() {
        // OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI only (not the OIDF_* form)
        OidfEnvOverrides.withEnv(
            mapOf("OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" to "https://kc.example/realms/oidf"),
        ) {
            assertEquals(
                "https://kc.example/realms/oidf",
                LegacyEnvMappingPropertySource.getProperty(OidfConfigKeys.OAuth2.ISSUER_URI),
            )
        }

        // DEV_MODE only (not APP_DEV_MODE)
        OidfEnvOverrides.withEnv(mapOf("DEV_MODE" to "true")) {
            assertEquals(
                "true",
                LegacyEnvMappingPropertySource.getProperty(OidfConfigKeys.Federation.DEV_MODE),
            )
        }

        // APP_DEV_MODE only
        OidfEnvOverrides.withEnv(mapOf("APP_DEV_MODE" to "true")) {
            assertEquals(
                "true",
                LegacyEnvMappingPropertySource.getProperty(OidfConfigKeys.Federation.DEV_MODE),
            )
        }
    }

    @Test
    fun getAllLegacyProperties_maps_set_vars_to_idk_keys() {
        OidfEnvOverrides.withEnv(
            mapOf(
                "ROOT_IDENTIFIER" to "https://root.example",
                "DATASOURCE_URL" to "jdbc:postgresql://db/oidf",
                "KMS_PROVIDER" to "azure",
            ),
        ) {
            val all = LegacyEnvMappingPropertySource.getAllLegacyProperties()
            assertEquals("https://root.example", all[OidfConfigKeys.Federation.ROOT_IDENTIFIER])
            assertEquals("jdbc:postgresql://db/oidf", all[OidfConfigKeys.Datasource.URL])
            assertEquals("azure", all[OidfConfigKeys.Kms.DEFAULT_PROVIDER])
            assertTrue(LegacyEnvMappingPropertySource.hasLegacyEnvVars())
            assertTrue("ROOT_IDENTIFIER" in LegacyEnvMappingPropertySource.getActiveLegacyEnvVars())
        }
    }

    @Test
    fun isolated_empty_env_reports_no_legacy_vars() {
        OidfEnvOverrides.withEnv(emptyMap()) {
            assertFalse(LegacyEnvMappingPropertySource.hasLegacyEnvVars())
            assertTrue(LegacyEnvMappingPropertySource.getActiveLegacyEnvVars().isEmpty())
            assertTrue(LegacyEnvMappingPropertySource.getAllLegacyProperties().isEmpty())
            assertEquals(
                "fallback",
                LegacyEnvMappingPropertySource.getProperty(OidfConfigKeys.Federation.ROOT_IDENTIFIER, "fallback"),
            )
        }
    }
}

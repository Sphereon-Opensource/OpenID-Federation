package com.sphereon.openid.fed.common.config

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.core.config.OidfConfigBootstrap
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfFilePropertySource
import com.sphereon.openid.fed.core.config.OidfPropertyResolution
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive regression suite: every supported env var reaches the shared
 * [OidfPropertyResolution] pipeline (and thus installers that only set env vars).
 *
 * Uses [OidfEnvOverrides] so the suite does not depend on the host process env.
 */
class OidfEnvVarRegressionTest {

    @BeforeTest
    fun setUp() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        clearOidfTestKeysFromAppMap()
        OidfFilePropertySource.clear()
        // Re-install after resetForTests() clears OidfConfigSources
        OidfConfigEnvironment.install()
    }

    @AfterTest
    fun tearDown() {
        OidfEnvOverrides.clear()
        OidfConfigBootstrap.resetForTests()
        clearOidfTestKeysFromAppMap()
        OidfFilePropertySource.clear()
    }

    private fun clearOidfTestKeysFromAppMap() {
        DefaultAppMapPropertySource.getSource().keys
            .filter { it.startsWith("oidf.") || it.startsWith("kms.providers.") }
            .toList()
            .forEach { DefaultAppMapPropertySource.deleteProperty(it) }
    }

    private fun resolve(idkKey: String): String {
        // Environment tier installed once for the suite (config system, not per-call callback)
        if (!com.sphereon.openid.fed.core.config.OidfConfigSources.isEnvironmentInstalled()) {
            com.sphereon.openid.fed.core.config.OidfConfigSources.installEnvironment {
                getEnvironmentVariable(it)
            }
        }
        return OidfPropertyResolution.resolveString(key = idkKey, default = "")
    }

    // ------------------------------------------------------------------
    // IDK-normalized OIDF_* vars for every OidfConfigKeys property in use
    // ------------------------------------------------------------------

    @Test
    fun all_oidf_normalized_env_vars_resolve_via_property_pipeline() {
        val samples = oidfNormalizedSamples()
        OidfEnvOverrides.withEnv(samples.mapKeys { (idk, _) -> normalizeKeyForEnv(idk) }) {
            samples.forEach { (idkKey, expected) ->
                assertEquals(expected, resolve(idkKey), "normalized env for $idkKey")
                assertEquals(expected, getEnvironmentVariable(idkKey), "getEnvironmentVariable($idkKey)")
            }
        }
    }

    // ------------------------------------------------------------------
    // Every legacy mapping entry individually
    // ------------------------------------------------------------------

    @Test
    fun every_legacy_mapping_entry_resolves_when_set_alone() {
        LegacyEnvMappingPropertySource.legacyMappings.forEach { (envName, idkKey) ->
            val marker = "marker-for-$envName"
            OidfEnvOverrides.withEnv(mapOf(envName to marker)) {
                assertEquals(
                    marker,
                    resolve(idkKey),
                    "legacy env $envName should resolve idk key $idkKey",
                )
                assertEquals(
                    marker,
                    getEnvironmentVariable(idkKey),
                    "getEnvironmentVariable($idkKey) via $envName",
                )
                // Direct env name also works
                assertEquals(marker, getEnvironmentVariable(envName), "direct $envName")
            }
        }
    }

    @Test
    fun full_legacy_deployment_profile_from_env_example() {
        // Mirrors a typical docker/.env.example install using legacy names only
        val env = mapOf(
            "ROOT_IDENTIFIER" to "http://localhost:8080",
            "DATASOURCE_URL" to "jdbc:postgresql://db:5432/openid-federation-db",
            "DATASOURCE_USER" to "openid-federation-db-user",
            "DATASOURCE_PASSWORD" to "openid-federation-db-password",
            "DATASOURCE_DB" to "openid-federation-db",
            "OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" to "http://keycloak:8080/realms/openid-federation",
            "DEV_MODE" to "false",
            "LOGGER_SEVERITY" to "Verbose",
            "CORS_ALLOWED_ORIGINS" to "*",
            "CORS_ALLOWED_METHODS" to "GET,POST,PUT,DELETE,OPTIONS",
            "CORS_ALLOWED_HEADERS" to "Authorization,Content-Type,X-Account-Username",
            "CORS_MAX_AGE" to "3600",
            "KMS_PROVIDER" to "memory",
            "LOGGER_OUTPUT" to "JSON",
        )

        OidfEnvOverrides.withEnv(env) {
            assertEquals("http://localhost:8080", resolve(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            assertEquals("jdbc:postgresql://db:5432/openid-federation-db", resolve(OidfConfigKeys.Datasource.URL))
            assertEquals("openid-federation-db-user", resolve(OidfConfigKeys.Datasource.USER))
            assertEquals("openid-federation-db-password", resolve(OidfConfigKeys.Datasource.PASSWORD))
            assertEquals("openid-federation-db", resolve(OidfConfigKeys.Datasource.DB))
            assertEquals(
                "http://keycloak:8080/realms/openid-federation",
                resolve(OidfConfigKeys.OAuth2.ISSUER_URI),
            )
            assertEquals("false", resolve(OidfConfigKeys.Federation.DEV_MODE))
            assertEquals("Verbose", resolve(OidfConfigKeys.Logger.SEVERITY))
            assertEquals("JSON", resolve(OidfConfigKeys.Logger.OUTPUT))
            assertEquals("*", resolve(OidfConfigKeys.Cors.ALLOWED_ORIGINS))
            assertEquals("GET,POST,PUT,DELETE,OPTIONS", resolve(OidfConfigKeys.Cors.ALLOWED_METHODS))
            assertEquals(
                "Authorization,Content-Type,X-Account-Username",
                resolve(OidfConfigKeys.Cors.ALLOWED_HEADERS),
            )
            assertEquals("3600", resolve(OidfConfigKeys.Cors.MAX_AGE))
            assertEquals("memory", resolve(OidfConfigKeys.Kms.DEFAULT_PROVIDER))
        }
    }

    @Test
    fun full_idk_normalized_deployment_profile() {
        val env = mapOf(
            "OIDF_FEDERATION_ROOT_IDENTIFIER" to "https://fed.example",
            "OIDF_DATASOURCE_URL" to "jdbc:postgresql://db/oidf",
            "OIDF_DATASOURCE_USER" to "oidf",
            "OIDF_DATASOURCE_PASSWORD" to "secret",
            "OIDF_DATASOURCE_DB" to "oidf",
            "OIDF_OAUTH2_ISSUER_URI" to "https://as.example/realms/x",
            "OIDF_OAUTH2_AUDIENCE" to "openid-federation-admin",
            "OIDF_OAUTH2_JWT_AUTH_ENABLED" to "true",
            "OIDF_IDENTITY_MODE" to "external",
            "OIDF_EXTERNAL_ROOT_TENANT_ID" to "tenant-root",
            "OIDF_ACCOUNT_HEADER_PRINCIPAL_CLAIM" to "preferred_username",
            "OIDF_ACCOUNT_HEADER_ALLOWED_PRINCIPALS" to "ops,bot",
            "OIDF_SESSION_ALIGNMENT" to "fixed",
            "OIDF_SESSION_FIXED_TENANT_ID" to "fixed-tenant",
            "OIDF_SERVER_ADMIN_PORT" to "18081",
            "OIDF_SERVER_FEDERATION_PORT" to "18080",
            "OIDF_FEDERATION_DEV_MODE" to "true",
            "OIDF_KMS_DEFAULT_PROVIDER" to "aws",
            "OIDF_LOGGER_SEVERITY" to "DEBUG",
            "OIDF_LOGGER_OUTPUT" to "TEXT",
            "OIDF_CORS_ALLOWED_ORIGINS" to "https://app.example",
        )

        OidfEnvOverrides.withEnv(env) {
            assertEquals("https://fed.example", resolve(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            assertEquals("jdbc:postgresql://db/oidf", resolve(OidfConfigKeys.Datasource.URL))
            assertEquals("oidf", resolve(OidfConfigKeys.Datasource.USER))
            assertEquals("secret", resolve(OidfConfigKeys.Datasource.PASSWORD))
            assertEquals("https://as.example/realms/x", resolve(OidfConfigKeys.OAuth2.ISSUER_URI))
            assertEquals("openid-federation-admin", resolve(OidfConfigKeys.OAuth2.AUDIENCE))
            assertEquals("true", resolve(OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED))
            assertEquals("external", resolve(OidfConfigKeys.Identity.MODE))
            assertEquals("tenant-root", resolve(OidfConfigKeys.Identity.EXTERNAL_ROOT_TENANT_ID))
            assertEquals("preferred_username", resolve(OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM))
            assertEquals("ops,bot", resolve(OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS))
            assertEquals("fixed", resolve(OidfConfigKeys.Identity.SESSION_ALIGNMENT))
            assertEquals("fixed-tenant", resolve(OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID))
            assertEquals("18081", resolve(OidfConfigKeys.Server.Admin.PORT))
            assertEquals("18080", resolve(OidfConfigKeys.Server.Federation.PORT))
            assertEquals("true", resolve(OidfConfigKeys.Federation.DEV_MODE))
            assertEquals("aws", resolve(OidfConfigKeys.Kms.DEFAULT_PROVIDER))
            assertEquals("DEBUG", resolve(OidfConfigKeys.Logger.SEVERITY))
            assertEquals("TEXT", resolve(OidfConfigKeys.Logger.OUTPUT))
            assertEquals("https://app.example", resolve(OidfConfigKeys.Cors.ALLOWED_ORIGINS))
        }
    }

    @Test
    fun azure_and_aws_kms_legacy_env_vars_resolve() {
        val env = mapOf(
            "AZURE_KEYVAULT_URL" to "https://kv.vault.azure.net",
            "AZURE_KEYVAULT_TENANT_ID" to "tenant",
            "AZURE_KEYVAULT_CLIENT_ID" to "client",
            "AZURE_KEYVAULT_CLIENT_SECRET" to "az-secret",
            "AZURE_KEYVAULT_APPLICATION_ID" to "app-id",
            "AZURE_KEYVAULT_MAX_RETRIES" to "7",
            "AZURE_KEYVAULT_BASE_DELAY" to "100",
            "AZURE_KEYVAULT_MAX_DELAY" to "3000",
            "AWS_REGION" to "eu-west-1",
            "AWS_ACCESS_KEY_ID" to "AKIA...",
            "AWS_SECRET_ACCESS_KEY" to "aws-secret",
            "AWS_MAX_RETRIES" to "4",
            "AWS_BASE_DELAY" to "150",
            "AWS_MAX_DELAY" to "5000",
        )
        OidfEnvOverrides.withEnv(env) {
            assertEquals("https://kv.vault.azure.net", resolve("kms.providers.azure.keyvaulturl"))
            assertEquals("tenant", resolve("kms.providers.azure.tenantid"))
            assertEquals("client", resolve("kms.providers.azure.credentialopts.clientid"))
            assertEquals("az-secret", resolve("kms.providers.azure.credentialopts.clientsecret"))
            assertEquals("app-id", resolve("kms.providers.azure.applicationid"))
            assertEquals("7", resolve("kms.providers.azure.exponentialbackoffretreopts.maxretries"))
            assertEquals("100", resolve("kms.providers.azure.exponentialbackoffretreopts.basedelayinms"))
            assertEquals("3000", resolve("kms.providers.azure.exponentialbackoffretreopts.maxdelayinms"))
            assertEquals("eu-west-1", resolve("kms.providers.aws.region"))
            assertEquals("AKIA...", resolve("kms.providers.aws.accesskeyid"))
            assertEquals("aws-secret", resolve("kms.providers.aws.secretaccesskey"))
            assertEquals("4", resolve("kms.providers.aws.maxretries"))
            assertEquals("150", resolve("kms.providers.aws.basedelayms"))
            assertEquals("5000", resolve("kms.providers.aws.maxdelayms"))
        }
    }

    @Test
    fun env_beats_file_defaults_for_legacy_and_normalized() {
        OidfFilePropertySource.putAll(
            mapOf(OidfConfigKeys.Federation.ROOT_IDENTIFIER to "from-file"),
        )

        OidfEnvOverrides.withEnv(mapOf("ROOT_IDENTIFIER" to "from-legacy-env")) {
            assertEquals("from-legacy-env", resolve(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
        }

        OidfEnvOverrides.withEnv(mapOf("OIDF_FEDERATION_ROOT_IDENTIFIER" to "from-oidf-env")) {
            assertEquals("from-oidf-env", resolve(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
        }
    }

    @Test
    fun empty_isolated_env_does_not_leak_host_process_env_for_mapped_keys() {
        // Even if the host has ROOT_IDENTIFIER set, isolation must hide it.
        OidfEnvOverrides.withEnv(emptyMap()) {
            assertNull(rawGetenv("ROOT_IDENTIFIER"))
            assertNull(getEnvironmentVariable(OidfConfigKeys.Federation.ROOT_IDENTIFIER))
            // Resolution falls through to file/hardcoded defaults (not host env)
            val value = resolve(OidfConfigKeys.Federation.ROOT_IDENTIFIER)
            // Either empty (no defaults in resolution without hardcoded match) or hardcoded default
            assertTrue(
                value.isEmpty() || value == "http://localhost:8080",
                "unexpected value under empty env: $value",
            )
        }
    }

    @Test
    fun catalog_size_is_stable_guard() {
        // Guard against accidental deletion of mappings. Bump intentionally when adding vars.
        val size = LegacyEnvMappingPropertySource.legacyMappings.size
        assertTrue(
            size >= 45,
            "expected at least 45 legacy/alias mappings, found $size — " +
                "if you intentionally removed entries, update this guard",
        )
        assertNotNull(LegacyEnvMappingPropertySource.legacyMappings)
    }

    companion object {
        /**
         * Representative sample values for each OidfConfigKeys key that installers
         * commonly set via normalized OIDF_* env vars.
         */
        fun oidfNormalizedSamples(): Map<String, String> = mapOf(
            OidfConfigKeys.Federation.ROOT_IDENTIFIER to "https://sample-root",
            OidfConfigKeys.Federation.DEV_MODE to "true",
            OidfConfigKeys.Server.Admin.PORT to "9081",
            OidfConfigKeys.Server.Admin.HOST to "127.0.0.1",
            OidfConfigKeys.Server.Federation.PORT to "9080",
            OidfConfigKeys.Server.Federation.HOST to "127.0.0.1",
            OidfConfigKeys.Datasource.URL to "jdbc:postgresql://sample/db",
            OidfConfigKeys.Datasource.USER to "sample-user",
            OidfConfigKeys.Datasource.PASSWORD to "sample-pass",
            OidfConfigKeys.Datasource.DB to "sample-db",
            OidfConfigKeys.OAuth2.ISSUER_URI to "https://sample-issuer",
            OidfConfigKeys.OAuth2.AUDIENCE to "sample-aud",
            OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED to "auto",
            OidfConfigKeys.Logger.SEVERITY to "WARN",
            OidfConfigKeys.Logger.OUTPUT to "JSON",
            OidfConfigKeys.Logger.INCLUDE_TIMESTAMP to "true",
            OidfConfigKeys.Cors.ALLOWED_ORIGINS to "https://a,https://b",
            OidfConfigKeys.Cors.ALLOWED_METHODS to "GET,POST",
            OidfConfigKeys.Cors.ALLOWED_HEADERS to "Authorization",
            OidfConfigKeys.Cors.MAX_AGE to "1200",
            OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
            OidfConfigKeys.Identity.MODE to "account",
            OidfConfigKeys.Identity.EXTERNAL_ROOT_TENANT_ID to "ext-root",
            OidfConfigKeys.Identity.PLATFORM_ROOT_TENANT_ID to "plat-root",
            OidfConfigKeys.Identity.SESSION_ALIGNMENT to "account",
            OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID to "fixed-1",
            OidfConfigKeys.Identity.ACCOUNT_HEADER_PRINCIPAL_CLAIM to "sub",
            OidfConfigKeys.Identity.ACCOUNT_HEADER_ALLOWED_PRINCIPALS to "a,b",
            OidfConfigKeys.Cache.HTTP_RESOLVER_LOCALITY to "LOCAL_ONLY",
            OidfConfigKeys.Cache.TRUST_CHAIN_LOCALITY to "LOCAL_PREFERRED",
            OidfConfigKeys.Jwe.ENABLED to "false",
        )
    }
}

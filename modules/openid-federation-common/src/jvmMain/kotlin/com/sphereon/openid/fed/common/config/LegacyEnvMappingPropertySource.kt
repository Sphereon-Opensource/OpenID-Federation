package com.sphereon.openid.fed.common.config

import com.sphereon.openid.fed.core.config.OidfConfigKeys

/**
 * Legacy environment variable mapping for backwards compatibility.
 *
 * This class maps old SCREAMING_CASE environment variables to the new
 * IDK-native oidf.* property keys. This allows existing deployments
 * to continue working without changing their environment variable names.
 *
 * ## Configuration Precedence (highest to lowest):
 *
 * 1. **IDK-normalized environment variables**
 *    - Key `oidf.federation.root.identifier` becomes `OIDF_FEDERATION_ROOT_IDENTIFIER`
 *
 * 2. **Legacy environment variables** (mapped via this class)
 *    - e.g., `ROOT_IDENTIFIER` maps to `oidf.federation.root.identifier`
 *
 * 3. **Hardcoded defaults** in [OidfConfigBinderImpl]
 *
 * ## Note on File-Based Config
 *
 * File-based configuration (application.properties, reference.conf) is NOT currently
 * implemented. The reference.conf file exists as documentation of available options,
 * but is not loaded at runtime. All configuration comes from environment variables.
 *
 * ## Usage Example
 *
 * ```bash
 * # Legacy (still works):
 * ROOT_IDENTIFIER=https://example.com ./gradlew :modules:openid-federation-server:run
 *
 * # New IDK-style (preferred):
 * OIDF_FEDERATION_ROOT_IDENTIFIER=https://example.com ./gradlew :modules:openid-federation-server:run
 * ```
 *
 * @see OidfConfigBinderImpl for the main configuration binding implementation
 */
object LegacyEnvMappingPropertySource {

    /**
     * Mapping from legacy SCREAMING_CASE env vars to IDK property keys.
     */
    val legacyMappings: Map<String, String> = mapOf(
        // Federation core settings
        "ROOT_IDENTIFIER" to OidfConfigKeys.Federation.ROOT_IDENTIFIER,
        "DEV_MODE" to OidfConfigKeys.Federation.DEV_MODE,
        "APP_DEV_MODE" to OidfConfigKeys.Federation.DEV_MODE,

        // Server settings
        "ADMIN_SERVER_PORT" to OidfConfigKeys.Server.Admin.PORT,
        "SERVER_PORT" to OidfConfigKeys.Server.Federation.PORT,

        // Datasource settings
        "DATASOURCE_URL" to OidfConfigKeys.Datasource.URL,
        "DATASOURCE_USER" to OidfConfigKeys.Datasource.USER,
        "DATASOURCE_PASSWORD" to OidfConfigKeys.Datasource.PASSWORD,
        "DATASOURCE_DB" to OidfConfigKeys.Datasource.DB,

        // OAuth2 / JWT settings
        "OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI" to OidfConfigKeys.OAuth2.ISSUER_URI,
        "OIDF_OAUTH2_ISSUER_URI" to OidfConfigKeys.OAuth2.ISSUER_URI,
        "OIDF_OAUTH2_AUDIENCE" to OidfConfigKeys.OAuth2.AUDIENCE,
        "OIDF_OAUTH2_JWT_AUTH_ENABLED" to OidfConfigKeys.OAuth2.JWT_AUTH_ENABLED,

        // Logger settings
        "LOGGER_SEVERITY" to OidfConfigKeys.Logger.SEVERITY,
        "SPHEREON_LOGGER_SEVERITY" to OidfConfigKeys.Logger.SEVERITY,
        "LOGGER_OUTPUT" to OidfConfigKeys.Logger.OUTPUT,
        "SPHEREON_LOGGER_OUTPUT" to OidfConfigKeys.Logger.OUTPUT,
        "SPHEREON_LOGGER_TIMESTAMP" to OidfConfigKeys.Logger.INCLUDE_TIMESTAMP,

        // CORS settings
        "CORS_ALLOWED_ORIGINS" to OidfConfigKeys.Cors.ALLOWED_ORIGINS,
        "CORS_ALLOWED_METHODS" to OidfConfigKeys.Cors.ALLOWED_METHODS,
        "CORS_ALLOWED_HEADERS" to OidfConfigKeys.Cors.ALLOWED_HEADERS,
        "CORS_MAX_AGE" to OidfConfigKeys.Cors.MAX_AGE,

        // KMS provider selection
        "KMS_PROVIDER" to OidfConfigKeys.Kms.DEFAULT_PROVIDER,

        // Identity / multi-tenancy mode
        "OIDF_IDENTITY_MODE" to OidfConfigKeys.Identity.MODE,
        "IDENTITY_MODE" to OidfConfigKeys.Identity.MODE,
        "OIDF_PLATFORM_ROOT_TENANT_ID" to OidfConfigKeys.Identity.PLATFORM_ROOT_TENANT_ID,
        "OIDF_SESSION_ALIGNMENT" to OidfConfigKeys.Identity.SESSION_ALIGNMENT,
        "OIDF_SESSION_FIXED_TENANT_ID" to OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID,

        // Azure Key Vault settings
        "AZURE_KEYVAULT_APPLICATION_ID" to "kms.providers.azure.applicationid",
        "AZURE_KEYVAULT_URL" to "kms.providers.azure.keyvaulturl",
        "AZURE_KEYVAULT_TENANT_ID" to "kms.providers.azure.tenantid",
        "AZURE_KEYVAULT_CLIENT_ID" to "kms.providers.azure.credentialopts.clientid",
        "AZURE_KEYVAULT_CLIENT_SECRET" to "kms.providers.azure.credentialopts.clientsecret",
        "AZURE_KEYVAULT_MAX_RETRIES" to "kms.providers.azure.exponentialbackoffretreopts.maxretries",
        "AZURE_KEYVAULT_BASE_DELAY" to "kms.providers.azure.exponentialbackoffretreopts.basedelayinms",
        "AZURE_KEYVAULT_MAX_DELAY" to "kms.providers.azure.exponentialbackoffretreopts.maxdelayinms",

        // AWS KMS settings
        "AWS_REGION" to "kms.providers.aws.region",
        "AWS_ACCESS_KEY_ID" to "kms.providers.aws.accesskeyid",
        "AWS_SECRET_ACCESS_KEY" to "kms.providers.aws.secretaccesskey",
        "AWS_MAX_RETRIES" to "kms.providers.aws.maxretries",
        "AWS_BASE_DELAY" to "kms.providers.aws.basedelayms",
        "AWS_MAX_DELAY" to "kms.providers.aws.maxdelayms"
    )

    /**
     * Reverse mapping from IDK property keys to legacy env var names.
     */
    val reverseMappings: Map<String, String> by lazy {
        legacyMappings.entries.associate { (legacy, idk) -> idk to legacy }
    }

    /**
     * Get a property value by trying the IDK key first, then falling back to
     * legacy environment variable lookup.
     *
     * @param idkKey The IDK-style property key (e.g., "oidf.federation.root.identifier")
     * @param default Default value if not found
     * @return The property value or default
     */
    fun getProperty(idkKey: String, default: String = ""): String {
        // First try direct environment variable with IDK key (normalized)
        val normalizedKey = normalizeForEnv(idkKey)
        System.getenv(normalizedKey)?.let { return it }

        // Then try legacy environment variable
        val legacyKey = reverseMappings[idkKey]
        if (legacyKey != null) {
            System.getenv(legacyKey)?.let { return it }
        }

        return default
    }

    /**
     * Get all properties from legacy environment variables, mapped to IDK keys.
     *
     * @return Map of IDK property keys to their values from legacy env vars
     */
    fun getAllLegacyProperties(): Map<String, String> {
        return legacyMappings.mapNotNull { (legacyKey, idkKey) ->
            System.getenv(legacyKey)?.let { value -> idkKey to value }
        }.toMap()
    }

    /**
     * Check if any legacy environment variables are set.
     * Useful for logging deprecation warnings.
     */
    fun hasLegacyEnvVars(): Boolean {
        return legacyMappings.keys.any { System.getenv(it) != null }
    }

    /**
     * Get list of legacy environment variables that are currently set.
     */
    fun getActiveLegacyEnvVars(): List<String> {
        return legacyMappings.keys.filter { System.getenv(it) != null }
    }

    /**
     * Normalize a property key for environment variable lookup.
     * Converts dots to underscores and makes uppercase.
     *
     * e.g., "oidf.federation.root.identifier" -> "OIDF_FEDERATION_ROOT_IDENTIFIER"
     */
    private fun normalizeForEnv(key: String): String {
        return key.replace(".", "_").uppercase()
    }
}

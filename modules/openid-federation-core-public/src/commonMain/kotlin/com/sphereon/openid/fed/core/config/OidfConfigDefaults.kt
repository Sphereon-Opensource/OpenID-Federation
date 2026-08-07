package com.sphereon.openid.fed.core.config

/**
 * Hardcoded APP-scope defaults for OpenID Federation.
 *
 * These mirror `reference.conf` until full HOCON loading is wired through IDK
 * property sources. Values here are lowest precedence after env + app map.
 */
object OidfConfigDefaults {
    val appProperties: Map<String, String> = mapOf(
        OidfConfigKeys.Federation.ROOT_IDENTIFIER to "http://localhost:8080",
        OidfConfigKeys.Federation.DEV_MODE to "false",
        OidfConfigKeys.Server.Admin.PORT to "8081",
        OidfConfigKeys.Server.Admin.HOST to "0.0.0.0",
        OidfConfigKeys.Server.Federation.PORT to "8080",
        OidfConfigKeys.Server.Federation.HOST to "0.0.0.0",
        OidfConfigKeys.Cors.ALLOWED_ORIGINS to "*",
        OidfConfigKeys.Cors.ALLOWED_METHODS to "GET,POST,PUT,DELETE,OPTIONS",
        OidfConfigKeys.Cors.ALLOWED_HEADERS to "*",
        OidfConfigKeys.Cors.MAX_AGE to "3600",
        OidfConfigKeys.Logger.SEVERITY to "INFO",
        OidfConfigKeys.Logger.OUTPUT to "TEXT",
        OidfConfigKeys.Logger.INCLUDE_TIMESTAMP to "false",
        OidfConfigKeys.Kms.DEFAULT_PROVIDER to "memory",
        OidfConfigKeys.Identity.MODE to "legacy",
        OidfConfigKeys.Identity.ALLOW_ANONYMOUS_ADMIN to "false",
        OidfConfigKeys.Identity.SESSION_ALIGNMENT to "account",
        OidfConfigKeys.Identity.SESSION_FIXED_TENANT_ID to "default",
    )

    /**
     * Default in-memory software KMS provider keys (IDK pattern).
     *
     * ## Boundary
     * - Un-namespaced `kms.providers.*` / `kms.keystores.*`: used on app **and** principal maps
     *   (session [com.sphereon.crypto.core.kms.KeyManagerService] reads principal config).
     * - Namespaced `{appNamespace}.kms.*`: app-scoped binders that expect `appId.profile` keys.
     *
     * Seeded via [OidfConfigBootstrap] into IDK [com.sphereon.core.api.conf.DefaultAppMapPropertySource]
     * so KeyManagerService and OidfConfigBinder share one pipeline.
     *
     * @param appNamespace typically `"{appId}.{profile}"` (must match AppGraph ids)
     */
    fun defaultSoftwareKmsProperties(appNamespace: String): Map<String, String> {
        // Un-namespaced provider keys (principal + app resolution).
        val providerProps = mapOf(
            "kms.providers.memory.type" to "software",
            "kms.providers.memory.id" to "memory",
            "kms.providers.memory.enabled" to "true",
            "kms.providers.memory.order" to "100",
            "kms.providers.memory.persistKeysDuringGeneration" to "true",
            "kms.providers.memory.exposePrivateKeysDuringGeneration" to "true",
            "kms.providers.memory.keyStore.type" to "memory",
            "kms.providers.memory.keyStore.id" to "oidfmemorykeystore",
            "kms.providers.memory.keyStore.keyVisibility" to "private",
            "kms.providers.memory.keyStore.scopeBinding" to "app",
            "kms.providers.memory.keyStore.overwriteAlias" to "true",
            "kms.keystores.oidfmemorykeystore.type" to "memory",
            "kms.keystores.oidfmemorykeystore.id" to "oidfmemorykeystore",
            "kms.keystores.oidfmemorykeystore.keyVisibility" to "private",
            "kms.keystores.oidfmemorykeystore.scopeBinding" to "app",
        )
        // App-scoped namespaced keys for binders that expect appId.profile.
        val namespaced = providerProps.mapKeys { (k, _) -> "$appNamespace.$k" }
        return providerProps + namespaced
    }
}

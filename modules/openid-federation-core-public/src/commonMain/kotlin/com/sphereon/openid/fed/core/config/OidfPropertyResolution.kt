package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.DefaultAppMapPropertySource

/**
 * Shared property resolution for OIDF configuration.
 *
 * ## Precedence (highest first)
 * 1. Optional [AppConfigService] (full IDK config pipeline when the host graph provides it)
 * 2. [DefaultAppMapPropertySource] — **explicit programmatic** overrides only
 *    (KMS provider maps, test injects). Must not hold silent oidf.* defaults that
 *    would shadow environment variables.
 * 3. Environment variables (IDK-normalized + legacy aliases via [envLookup])
 * 4. [OidfFilePropertySource] — classpath/file defaults (`reference.properties`,
 *    `reference.conf`, `application.properties`)
 * 5. [OidfConfigDefaults] hardcoded map
 *
 * ## Secrets boundary
 * Sensitive keys (passwords, client secrets) should prefer opaque secret handles
 * (`oidf.*.secret.id`) resolved by [com.sphereon.core.api.conf.OpaqueSecretResolver]
 * at use sites that can suspend (e.g. DatabaseConfig). This helper stays **synchronous**
 * and must not log values for keys matching [isSensitiveKey].
 *
 * Do not reintroduce a parallel HOCON/env stack — extend this pipeline or IDK property sources.
 */
object OidfPropertyResolution {

    /**
     * Suffixes / fragments that mark a property as sensitive (never log cleartext values).
     */
    val sensitiveKeySuffixes: Set<String> = setOf(
        "password",
        "clientsecret",
        "client.secret",
        "secretaccesskey",
        "secret.access.key",
        "accesskeyid",
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return sensitiveKeySuffixes.any {
            normalized.endsWith(it) || normalized.contains(".$it") || normalized.contains("_$it")
        }
    }

    fun resolveString(
        key: String,
        default: String = "",
        envLookup: (String) -> String?,
        appConfig: AppConfigService? = null,
    ): String {
        // 1) Full IDK AppConfigService when host injects one
        appConfig?.getPropertyAsString(key, null)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 2) Explicit app-map overrides (KMS bootstrap, tests)
        DefaultAppMapPropertySource.getPropertyAsString(key)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 3) Environment (deployer wins over packaged file defaults)
        envLookup(key)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 4) File / classpath tier
        OidfFilePropertySource.get(key)?.let { return it }

        // 5) Hardcoded defaults
        return OidfConfigDefaults.appProperties[key] ?: default
    }

    fun resolveStringOrNull(
        key: String,
        envLookup: (String) -> String?,
        appConfig: AppConfigService? = null,
    ): String? {
        val value = resolveString(key, default = "", envLookup = envLookup, appConfig = appConfig)
        return value.takeIf { it.isNotEmpty() }
    }
}

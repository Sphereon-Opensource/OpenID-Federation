package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.DefaultAppMapPropertySource

/**
 * Shared property resolution for OIDF configuration.
 *
 * Application code must not call `System.getenv` / `System.getProperty` for OIDF settings —
 * use [OidfConfigBinder] or this resolver.
 *
 * ## Precedence (highest first)
 * 1. [AppConfigService] — from argument or [OidfConfigSources.appConfig]
 *    (IDK Env, [com.sphereon.core.api.conf.PropertySourceContribution]s e.g. oidf-legacy-env, cloud, …)
 * 2. [DefaultAppMapPropertySource] — programmatic overrides (KMS bootstrap, tests)
 * 3. Environment tier via [OidfConfigSources.environment] — installed once at process start
 *    (not a per-call envLookup parameter)
 * 4. [OidfFilePropertySource] — OIDFed reference defaults only (`reference.properties` / `.conf`)
 * 5. [OidfConfigDefaults]
 *
 * Nested `application.yaml` is **not** parsed here — IDK `lib-conf-yaml` contributes it
 * into [AppConfigService] (and tenant/principal YAML into session config services).
 *
 * ## Tenant / principal
 * Session-scoped overrides are IDK's job ([TenantConfigService] / [PrincipalConfigService]).
 * This resolver is the APP-level pipeline used by [OidfConfigBinder].
 *
 * ## Secrets
 * Prefer opaque secret handles (`oidf.*.secret.id`) at suspend use sites. This helper is synchronous
 * and must not log values for keys matching [isSensitiveKey].
 */
object OidfPropertyResolution {

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

    /**
     * @param appConfig Prefer the injected AppConfigService; falls back to [OidfConfigSources.appConfig]
     */
    fun resolveString(
        key: String,
        default: String = "",
        appConfig: AppConfigService? = null,
    ): String {
        val config = appConfig ?: OidfConfigSources.appConfig

        // 1) Full IDK AppConfigService (Env + contributions when registered)
        config?.getPropertyAsString(key, null)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 2) Explicit app-map overrides (KMS bootstrap, tests)
        DefaultAppMapPropertySource.getPropertyAsString(key)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 3) Installed environment source (config system tier — not ad-hoc getenv)
        OidfConfigSources.environment(key)?.takeIf { it.isNotEmpty() }?.let { return it }

        // 4) File / classpath tier
        OidfFilePropertySource.get(key)?.let { return it }

        // 5) Hardcoded defaults
        return OidfConfigDefaults.appProperties[key] ?: default
    }

    fun resolveStringOrNull(
        key: String,
        appConfig: AppConfigService? = null,
    ): String? {
        val value = resolveString(key, default = "", appConfig = appConfig)
        return value.takeIf { it.isNotEmpty() }
    }
}

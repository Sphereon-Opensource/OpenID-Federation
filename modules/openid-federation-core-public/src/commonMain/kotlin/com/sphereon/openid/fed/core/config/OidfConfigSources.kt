package com.sphereon.openid.fed.core.config

import com.sphereon.core.api.conf.AppConfigService

/**
 * Process-wide handles for the OIDF configuration pipeline.
 *
 * ## Why this exists
 * Config must be readable **before** AppGraph exists (e.g. [com.sphereon.openid.fed.persistence.config.DatabaseConfig])
 * and **after** the graph has [AppConfigService] with IDK Env + property contributions.
 *
 * The environment is **not** an ad-hoc callback on every call site. It is installed once as the
 * config system's environment tier (JVM: [com.sphereon.openid.fed.common.config.getEnvironmentVariable]
 * covering IDK Env + legacy aliases). Callers use [OidfPropertyResolution] / [OidfConfigBinder] only.
 *
 * ## Tenant / principal overrides
 * IDK already applies tenant- and principal-scoped property sources via
 * [com.sphereon.core.api.conf.TenantConfigService] / [com.sphereon.core.api.conf.PrincipalConfigService]
 * on the session graph. Hosts that contribute tenant/principal sources get overrides automatically
 * when code reads the **session** config service. [OidfConfigBinder] is **App-scoped** and binds
 * APP-level OIDFed settings (including static file keys `oidf.tenant.<id>.*` for federation entity
 * layout). It does not reimplement IDK scope cascading.
 */
object OidfConfigSources {
    @Volatile
    var appConfig: AppConfigService? = null
        private set

    @Volatile
    private var environmentLookup: ((String) -> String?)? = null

    /**
     * Install the environment config source (once per process, or replace in tests).
     * Production: pass `{ getEnvironmentVariable(it) }` from the common module.
     */
    fun installEnvironment(lookup: (String) -> String?) {
        environmentLookup = lookup
    }

    /**
     * Bind the live [AppConfigService] after AppGraph init (Env + contributions already registered).
     */
    fun bindAppConfig(config: AppConfigService?) {
        appConfig = config
    }

    fun environment(key: String): String? = environmentLookup?.invoke(key)

    fun isEnvironmentInstalled(): Boolean = environmentLookup != null

    fun resetForTests() {
        appConfig = null
        environmentLookup = null
    }
}

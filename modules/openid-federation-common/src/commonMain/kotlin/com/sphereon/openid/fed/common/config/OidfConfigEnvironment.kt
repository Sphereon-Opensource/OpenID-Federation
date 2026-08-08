package com.sphereon.openid.fed.common.config

import com.sphereon.openid.fed.core.config.OidfConfigSources

/**
 * Installs the OIDF environment tier into [OidfConfigSources] using
 * [getEnvironmentVariable] (IDK Env + legacy aliases on JVM).
 *
 * Call before any config resolution that needs process environment
 * ([OidfConfigBootstrap.seed], server main, [DatabaseConfig]).
 * Idempotent.
 */
object OidfConfigEnvironment {
    fun install() {
        if (!OidfConfigSources.isEnvironmentInstalled()) {
            OidfConfigSources.installEnvironment { key -> getEnvironmentVariable(key) }
        }
    }
}

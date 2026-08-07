package com.sphereon.openid.fed.persistence.config

import com.sphereon.openid.fed.common.config.OidfConfigEnvironment
import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfConfigSources
import com.sphereon.openid.fed.core.config.OidfPropertyResolution
import com.sphereon.openid.fed.core.security.EnvOpaqueSecretResolver
import com.sphereon.openid.fed.core.security.secretIdKeyFor
import kotlinx.coroutines.runBlocking

/**
 * Database configuration via the shared OIDF property pipeline ([OidfPropertyResolution]).
 *
 * Ensures [OidfConfigEnvironment] is installed so process env participates as the config
 * system's environment tier (not a parallel getenv path). Prefers [OidfConfigSources.appConfig]
 * when the AppGraph has bound it.
 */
class DatabaseConfig {
    init {
        OidfConfigEnvironment.install()
    }

    val url: String = getRequiredValue(
        OidfConfigKeys.Datasource.URL,
        "Database URL not configured. Set oidf.datasource.url / OIDF_DATASOURCE_URL / DATASOURCE_URL",
    )

    val username: String = getRequiredValue(
        OidfConfigKeys.Datasource.USER,
        "Database username not configured. Set oidf.datasource.user / OIDF_DATASOURCE_USER / DATASOURCE_USER",
    )

    val password: String = resolvePassword()

    private fun resolvePassword(): String {
        val secretId = OidfPropertyResolution.resolveStringOrNull(
            secretIdKeyFor(OidfConfigKeys.Datasource.PASSWORD),
        )
        if (!secretId.isNullOrBlank()) {
            // Opaque secrets still resolve via the same env tier as getEnvironmentVariable
            val resolver = EnvOpaqueSecretResolver { getEnvironmentVariable(it) }
            val resolved = runBlocking { resolver.resolve(secretId) }
            if (resolved.isOk) return resolved.value
            throw IllegalStateException(
                "Database password secret id '$secretId' could not be resolved. " +
                    "Set OIDF_SECRET_${secretId.replace(".", "_").uppercase()} or use cleartext password.",
            )
        }
        return getRequiredValue(
            OidfConfigKeys.Datasource.PASSWORD,
            "Database password not configured. Set oidf.datasource.password / DATASOURCE_PASSWORD " +
                "or oidf.datasource.password.secret.id",
        )
    }

    private fun getRequiredValue(idkKey: String, errorMessage: String): String {
        val value = OidfPropertyResolution.resolveString(key = idkKey, default = "")
        if (value.isEmpty()) {
            throw IllegalStateException(errorMessage)
        }
        return value
    }
}

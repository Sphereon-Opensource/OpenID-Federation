package com.sphereon.openid.fed.persistence.config

import com.sphereon.openid.fed.common.config.getEnvironmentVariable
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import com.sphereon.openid.fed.core.config.OidfPropertyResolution
import com.sphereon.openid.fed.core.security.EnvOpaqueSecretResolver
import com.sphereon.openid.fed.core.security.secretIdKeyFor
import kotlinx.coroutines.runBlocking

/**
 * Database configuration loaded via the shared OIDF property pipeline.
 *
 * Supports:
 * - [com.sphereon.core.api.conf.DefaultAppMapPropertySource]
 * - IDK-normalized environment variables (OIDF_DATASOURCE_URL)
 * - Legacy environment variables (DATASOURCE_URL, DATASOURCE_USER, etc.)
 *
 * Password may be supplied as cleartext (`oidf.datasource.password`) or via opaque
 * secret id (`oidf.datasource.password.secret.id` → [EnvOpaqueSecretResolver]).
 *
 * Note: Database configuration is required; missing values will throw IllegalStateException.
 */
class DatabaseConfig {
    val url: String = getRequiredValue(
        OidfConfigKeys.Datasource.URL,
        "Database URL not configured. Set DATASOURCE_URL or oidf.datasource.url"
    )

    val username: String = getRequiredValue(
        OidfConfigKeys.Datasource.USER,
        "Database username not configured. Set DATASOURCE_USER or oidf.datasource.user"
    )

    val password: String = resolvePassword()

    private fun resolvePassword(): String {
        val secretId = OidfPropertyResolution.resolveStringOrNull(
            secretIdKeyFor(OidfConfigKeys.Datasource.PASSWORD),
            envLookup = { getEnvironmentVariable(it) },
        )
        if (!secretId.isNullOrBlank()) {
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
            "Database password not configured. Set DATASOURCE_PASSWORD, oidf.datasource.password, " +
                "or oidf.datasource.password.secret.id",
        )
    }

    private fun getRequiredValue(idkKey: String, errorMessage: String): String {
        val value = OidfPropertyResolution.resolveString(
            key = idkKey,
            default = "",
            envLookup = { getEnvironmentVariable(it) },
        )
        if (value.isEmpty()) {
            throw IllegalStateException(errorMessage)
        }
        return value
    }
}

package com.sphereon.openid.fed.persistence.config

import com.sphereon.openid.fed.common.config.LegacyEnvMappingPropertySource
import com.sphereon.openid.fed.core.config.OidfConfigKeys

/**
 * Database configuration loaded via IDK configuration system.
 *
 * Supports:
 * - IDK-normalized environment variables (OIDF_DATASOURCE_URL)
 * - Legacy environment variables (DATASOURCE_URL, DATASOURCE_USER, etc.)
 * - reference.conf defaults (empty, requiring explicit configuration)
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

    val password: String = getRequiredValue(
        OidfConfigKeys.Datasource.PASSWORD,
        "Database password not configured. Set DATASOURCE_PASSWORD or oidf.datasource.password"
    )

    private fun getRequiredValue(idkKey: String, errorMessage: String): String {
        val value = LegacyEnvMappingPropertySource.getProperty(idkKey, "")
        if (value.isEmpty()) {
            throw IllegalStateException(errorMessage)
        }
        return value
    }
}

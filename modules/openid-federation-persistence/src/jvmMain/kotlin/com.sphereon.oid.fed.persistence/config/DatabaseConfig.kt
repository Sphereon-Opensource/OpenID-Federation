package com.sphereon.oid.fed.persistence.config

import com.sphereon.oid.fed.common.Constants

class DatabaseConfig {
    val url: String = getRequiredEnvValue(Constants.DATASOURCE_URL, "Database URL not configured")
    val username: String = getRequiredEnvValue(Constants.DATASOURCE_USER, "Database username not configured")
    val password: String = getRequiredEnvValue(Constants.DATASOURCE_PASSWORD, "Database password not configured")

    private fun getRequiredEnvValue(key: String, errorMessage: String): String {
        return System.getenv(key) ?: throw IllegalStateException(errorMessage)
    }
}
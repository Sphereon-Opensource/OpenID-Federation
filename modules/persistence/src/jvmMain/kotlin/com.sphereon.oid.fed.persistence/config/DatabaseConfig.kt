package com.sphereon.oid.fed.persistence.config

import com.sphereon.oid.fed.common.Constants

/**
 * Configuration class for database connection settings.
 * Provides a centralized way to manage database configuration.
 */
class DatabaseConfig {
    val url: String = System.getenv(Constants.DATASOURCE_URL)
        ?: throw IllegalStateException("Database URL not configured")

    val username: String = System.getenv(Constants.DATASOURCE_USER)
        ?: throw IllegalStateException("Database username not configured")

    val password: String = System.getenv(Constants.DATASOURCE_PASSWORD)
        ?: throw IllegalStateException("Database password not configured")
}
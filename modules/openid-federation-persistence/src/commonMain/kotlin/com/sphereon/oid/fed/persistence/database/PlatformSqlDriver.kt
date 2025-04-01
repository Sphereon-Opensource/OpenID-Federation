package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.db.SqlDriver

/**
 * PlatformSqlDriver provides methods for creating SQL drivers to interact with different databases.
 * This class is set up to work with PostgreSQL and SQLite databases.
 */
expect class PlatformSqlDriver {
    fun createPostgresDriver(url: String, username: String, password: String): SqlDriver
    fun createSqliteDriver(path: String): SqlDriver
}

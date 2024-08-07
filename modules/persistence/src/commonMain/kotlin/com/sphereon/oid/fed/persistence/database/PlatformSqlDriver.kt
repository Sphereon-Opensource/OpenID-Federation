package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.db.SqlDriver

expect class PlatformSqlDriver {
    fun createPostgresDriver(url: String, username: String, password: String): SqlDriver
    fun createSqliteDriver(path: String): SqlDriver
}

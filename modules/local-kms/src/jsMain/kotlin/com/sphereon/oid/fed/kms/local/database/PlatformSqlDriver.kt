package com.sphereon.oid.fed.kms.local.database

import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.kms.local.Constants

actual class PlatformSqlDriver {
    actual fun createPostgresDriver(url: String, username: String, password: String): SqlDriver {
        throw UnsupportedOperationException(Constants.POSTGRESQL_IS_NOT_SUPPORTED_IN_JS) as Throwable
    }

    actual fun createSqliteDriver(path: String): SqlDriver {
        throw UnsupportedOperationException(Constants.SQLITE_IS_NOT_SUPPORTED_IN_JS)
    }
}

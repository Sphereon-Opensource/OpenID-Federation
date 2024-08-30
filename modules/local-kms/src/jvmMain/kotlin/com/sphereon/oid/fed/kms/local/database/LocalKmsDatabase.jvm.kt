package com.sphereon.oid.fed.kms.local.database

import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.kms.local.Constants
import com.sphereon.oid.fed.kms.local.Database
import com.sphereon.oid.fed.kms.local.models.Keys


actual class LocalKmsDatabase {

    private var database: Database

    init {
        val driver = getDriver()

        database = Database(driver)
    }

    private fun getDriver(): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DATASOURCE_URL),
            System.getenv(Constants.DATASOURCE_USER),
            System.getenv(Constants.DATASOURCE_PASSWORD)
        )
    }

    actual fun getKey(keyId: String): Keys {
        return database.keysQueries.findById(keyId).executeAsOneOrNull()
            ?: throw KeyNotFoundException("$keyId not found")
    }

    actual fun insertKey(keyId: String, privateKey: String) {
        database.keysQueries.create(keyId, privateKey).executeAsOneOrNull()
    }

    actual fun deleteKey(keyId: String) {
        database.keysQueries.delete(keyId)
    }
}

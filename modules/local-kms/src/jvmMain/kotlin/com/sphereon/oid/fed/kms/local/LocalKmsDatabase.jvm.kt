package com.sphereon.oid.fed.kms.local

import app.cash.sqldelight.db.SqlDriver
import com.sphereon.oid.fed.kms.local.models.Keys
import com.sphereon.oid.fed.persistence.Constants
import com.sphereon.oid.fed.persistence.database.PlatformSqlDriver


actual class LocalKmsDatabase {

    init {
        val driver = getDriver()

        val database = Database(driver)

    }

    private fun getDriver(): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            System.getenv(Constants.DATASOURCE_URL),
            System.getenv(Constants.DATASOURCE_USER),
            System.getenv(Constants.DATASOURCE_PASSWORD)
        )
    }

    actual fun getKey(keyId: String): Keys {
        TODO("Not yet implemented")
    }

    actual fun insertKey(
        keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String
    ) {
        TODO("Not yet implemented")
    }

    actual fun updateKey(
        keyId: String, privateKey: ByteArray, publicKey: ByteArray, algorithm: String
    ) {
        TODO("Not yet implemented")
    }

    actual fun deleteKey(keyId: String) {
        TODO("Not yet implemented")
    }
}
package com.sphereon.oid.fed.persistence

import DatabaseFactory
import Persistence
import PlatformSqlDriver

object PersistenceInitializer {
    fun initializePersistence(): Persistence {
        val platformSqlDriver = PlatformSqlDriver()
        val databaseFactory = DatabaseFactory(platformSqlDriver)

        val url = System.getenv("DATABASE_URL") ?: throw IllegalArgumentException("DATABASE_URL not set")
        val username = System.getenv("DATABASE_USERNAME") ?: throw IllegalArgumentException("DATABASE_USERNAME not set")
        val password = System.getenv("DATABASE_PASSWORD") ?: throw IllegalArgumentException("DATABASE_PASSWORD not set")

        val config = DatabaseConfig.Postgres(
            url = url,
            username = username,
            password = password
        )
        return Persistence(databaseFactory, config)
    }
}

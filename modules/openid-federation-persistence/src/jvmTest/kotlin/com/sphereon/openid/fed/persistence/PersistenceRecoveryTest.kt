/*
 * Copyright (c) 2026 Sphereon B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sphereon.openid.fed.persistence

import com.sphereon.core.api.conf.DefaultAppMapPropertySource
import com.sphereon.openid.fed.core.config.OidfConfigKeys
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A first use that fails (here: the database does not exist yet) must not leave [Persistence] permanently unusable.
 * The next use opens and migrates the database.
 */
class PersistenceRecoveryTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<Nothing>

        @BeforeClass
        @JvmStatic
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Persistence recovery needs Docker and a live PostgreSQL container",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("postgres")
                withUsername("owner")
                withPassword("owner")
                start()
            }
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres() {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun aFailedFirstUseIsRetriedOnTheNextUse() {
        val databaseName = "recovery_" + java.util.UUID.randomUUID().toString().replace("-", "")
        DefaultAppMapPropertySource.addProperties(
            mapOf(
                OidfConfigKeys.Datasource.URL to postgres.jdbcUrl.replace("/postgres", "/$databaseName"),
                OidfConfigKeys.Datasource.USER to "owner",
                OidfConfigKeys.Datasource.PASSWORD to "owner",
            ),
        )

        assertFailsWith<Throwable>("the database does not exist yet") { Persistence.accountQueries }

        DriverManager.getConnection(postgres.jdbcUrl, "owner", "owner").use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE DATABASE $databaseName") }
        }

        Persistence.accountQueries.findAll().executeAsList()
        DriverManager.getConnection(postgres.jdbcUrl.replace("/postgres", "/$databaseName"), "owner", "owner").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT MAX(version) FROM schema_version").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(Database.Schema.version, rows.getLong(1), "the retried use migrated the database")
                }
            }
        }
    }
}

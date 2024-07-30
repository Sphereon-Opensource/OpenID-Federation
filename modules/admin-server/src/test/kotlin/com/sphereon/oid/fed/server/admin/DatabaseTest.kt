package com.sphereon.oid.fed.server.admin

import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class DatabaseTest {

    @Container
    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:14")

    @Test
    fun `test database connection`() {
        assert(postgres.isRunning)
    }
}
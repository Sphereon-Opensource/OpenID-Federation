package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.logger.Logger.Severity
import com.sphereon.oid.fed.persistence.models.LogQueries
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import com.sphereon.oid.fed.persistence.models.Log as LogEntity

class LogServiceTest {

    private val mockLogQueries = mockk<LogQueries>(relaxed = true)
    private val logService = LogService(mockLogQueries)

    @Test
    fun `insertLog with no metadata or throwable`() {
        val severity = Severity.Info
        val message = "Test message"
        val tag = "TestTag"
        val timestamp = 1625123456789L

        logService.insertLog(
            severity = severity,
            message = message,
            tag = tag,
            timestamp = timestamp,
            throwable = null,
            metadata = emptyMap()
        )

        verify {
            mockLogQueries.insertLog(
                severity = severity.name,
                message = message,
                tag = tag,
                timestamp = timestamp,
                throwable_message = null,
                throwable_stacktrace = null,
                metadata = null
            )
        }
    }

    @Test
    fun `insertLog with throwable`() {
        val severity = Severity.Info
        val message = "Error message"
        val tag = "ErrorTag"
        val timestamp = 1625123456789L
        val throwable = RuntimeException("Test exception")

        logService.insertLog(
            severity = severity,
            message = message,
            tag = tag,
            timestamp = timestamp,
            throwable = throwable,
            metadata = emptyMap()
        )

        verify {
            mockLogQueries.insertLog(
                severity = any(),
                message = any(),
                tag = any(),
                timestamp = any(),
                throwable_message = "Test exception",
                throwable_stacktrace = match { it.contains("RuntimeException") },
                metadata = any()
            )
        }
    }

    @Test
    fun `insertLog with metadata`() {
        val severity = Severity.Info
        val message = "Metadata message"
        val tag = "MetadataTag"
        val timestamp = 1625123456789L
        val metadata = mapOf("key1" to "value1", "key2" to "value2")

        logService.insertLog(
            severity = severity,
            message = message,
            tag = tag,
            timestamp = timestamp,
            throwable = null,
            metadata = metadata
        )

        val expectedJson = Json.encodeToString(metadata)
        verify {
            mockLogQueries.insertLog(
                severity = any(),
                message = any(),
                tag = any(),
                timestamp = any(),
                throwable_message = any(),
                throwable_stacktrace = any(),
                metadata = expectedJson
            )
        }
    }

    @Test
    fun `getRecentLogs returns logs converted to DTOs`() {
        val mockLogs = listOf(
            LogEntity(
                id = 1,
                timestamp = 1625123456789L,
                severity = Severity.Info.name,
                tag = "TestTag",
                message = "Test message",
                throwable_message = null,
                throwable_stacktrace = null,
                metadata = null
            )
        )

        every { mockLogQueries.getRecentLogs(10) } returns mockk {
            every { executeAsList() } returns mockLogs
        }

        val result = logService.getRecentLogs(10)

        assertEquals(1, result.size)
        assertEquals("Test message", result[0].message)
        assertEquals("TestTag", result[0].tag)
    }

    @Test
    fun `searchLogs returns matching logs`() {
        val mockLogs = listOf(
            LogEntity(
                id = 1,
                timestamp = 1625123456789L,
                severity = Severity.Info.name,
                tag = "SearchTag",
                message = "Searchable content",
                throwable_message = null,
                throwable_stacktrace = null,
                metadata = null
            )
        )

        every { mockLogQueries.searchLogs("Searchable", 20) } returns mockk {
            every { executeAsList() } returns mockLogs
        }

        val result = logService.searchLogs("Searchable", 20)

        assertEquals(1, result.size)
        assertEquals("Searchable content", result[0].message)
        verify { mockLogQueries.searchLogs("Searchable", 20) }
    }

    @Test
    fun `getLogsBySeverity returns logs with matching severity`() {
        val errorSeverity = Severity.Error.name
        val mockLogs = listOf(
            LogEntity(
                id = 1,
                timestamp = 1625123456789L,
                severity = errorSeverity,
                tag = "ErrorTag",
                message = "Error message",
                throwable_message = "Some error",
                throwable_stacktrace = "Error stack trace",
                metadata = null
            )
        )

        every { mockLogQueries.getLogsBySeverity(errorSeverity, 15) } returns mockk {
            every { executeAsList() } returns mockLogs
        }

        val result = logService.getLogsBySeverity(errorSeverity, 15)

        assertEquals(1, result.size)
        assertEquals("Error message", result.first().message)
        verify { mockLogQueries.getLogsBySeverity(errorSeverity, 15) }
    }

    @Test
    fun `getLogsByTag returns logs with matching tag`() {
        val mockLogs = listOf(
            LogEntity(
                id = 1,
                timestamp = 1625123456789L,
                severity = Severity.Debug.name,
                tag = "SpecificTag",
                message = "Tagged message",
                throwable_message = null,
                throwable_stacktrace = null,
                metadata = null
            )
        )

        every { mockLogQueries.getLogsByTag("SpecificTag", 25) } returns mockk {
            every { executeAsList() } returns mockLogs
        }

        val result = logService.getLogsByTag("SpecificTag", 25)

        assertEquals(1, result.size)
        assertEquals("SpecificTag", result.first().tag)
        verify { mockLogQueries.getLogsByTag("SpecificTag", 25) }
    }
}

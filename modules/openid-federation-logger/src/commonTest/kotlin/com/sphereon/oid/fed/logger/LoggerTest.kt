package com.sphereon.oid.fed.logger

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LoggerTest {

    @Test
    fun `test verbose logging`() {
        val loggerConfig = LoggerConfig(severity = Logger.Severity.Verbose)
        val logger = Logger(tag = "testTag", config = loggerConfig)

        var loggedEvent: Logger.LogEvent? = null
        Logger.addLogWriter(object : Logger.LogWriter {
            override val minSeverity: Logger.Severity = Logger.Severity.Verbose
            override fun log(event: Logger.LogEvent) {
                loggedEvent = event
            }
        })

        val message = "Verbose log message"
        logger.verbose(message, metadata = mapOf("testKey" to "testValue"))

        assertEquals(Logger.Severity.Verbose, loggedEvent?.severity)
        assertEquals(message, loggedEvent?.message)
        assertEquals("testTag", loggedEvent?.tag)
    }

    @Test
    fun `test debug logging`() {
        val loggerConfig = LoggerConfig(severity = Logger.Severity.Debug)
        val logger = Logger(tag = "tagDebug", config = loggerConfig)

        var loggedEvent: Logger.LogEvent? = null
        Logger.addLogWriter(object : Logger.LogWriter {
            override val minSeverity: Logger.Severity = Logger.Severity.Debug
            override fun log(event: Logger.LogEvent) {
                loggedEvent = event
            }
        })

        val message = "Debugging something"
        logger.debug(message)

        assertEquals(Logger.Severity.Debug, loggedEvent?.severity)
        assertEquals(message, loggedEvent?.message)
        assertEquals("tagDebug", loggedEvent?.tag)
    }

    @Test
    fun `test info logging`() {
        val loggerConfig = LoggerConfig(severity = Logger.Severity.Info)
        val logger = Logger(tag = "InfoLogger", config = loggerConfig)

        var loggedEvent: Logger.LogEvent? = null
        Logger.addLogWriter(object : Logger.LogWriter {
            override val minSeverity: Logger.Severity = Logger.Severity.Info
            override fun log(event: Logger.LogEvent) {
                loggedEvent = event
            }
        })

        val message = "Information log"
        val metadata = mapOf("key" to "value")
        logger.info(message, metadata = metadata)

        assertEquals(Logger.Severity.Info, loggedEvent?.severity)
        assertEquals(message, loggedEvent?.message)
        assertEquals("InfoLogger", loggedEvent?.tag)
        assertEquals(metadata, loggedEvent?.metadata)
    }

    @Test
    fun `test warn logging`() {
        val loggerConfig = LoggerConfig(severity = Logger.Severity.Warn)
        val logger = Logger(tag = "WarnLogger", config = loggerConfig)

        var loggedEvent: Logger.LogEvent? = null
        Logger.addLogWriter(object : Logger.LogWriter {
            override val minSeverity: Logger.Severity = Logger.Severity.Warn
            override fun log(event: Logger.LogEvent) {
                loggedEvent = event
            }
        })

        val message = "Warning log"
        logger.warn(message)

        assertEquals(Logger.Severity.Warn, loggedEvent?.severity)
        assertEquals(message, loggedEvent?.message)
        assertEquals("WarnLogger", loggedEvent?.tag)
    }

    @Test
    fun `test error logging`() {
        val loggerConfig = LoggerConfig(severity = Logger.Severity.Error)
        val logger = Logger(tag = "ErrorLogger", config = loggerConfig)

        var loggedEvent: Logger.LogEvent? = null
        Logger.addLogWriter(object : Logger.LogWriter {
            override val minSeverity: Logger.Severity = Logger.Severity.Error
            override fun log(event: Logger.LogEvent) {
                loggedEvent = event
            }
        })

        val message = "Error occurred"
        val exception = RuntimeException("Test exception")
        logger.error(message, throwable = exception)

        assertEquals(Logger.Severity.Error, loggedEvent?.severity)
        assertEquals(message, loggedEvent?.message)
        assertEquals("ErrorLogger", loggedEvent?.tag)
        assertEquals(exception, loggedEvent?.throwable)
    }

    @Test
    fun `test log event formatted message`() {
        val logEvent = Logger.LogEvent(
            severity = Logger.Severity.Info,
            message = "Test log message",
            tag = "TestTag",
            timestamp = Clock.System.now().toEpochMilliseconds(),
            metadata = mapOf("key1" to "value1", "key2" to "value2")
        )

        val formattedMessage = logEvent.formattedMessage
        assertContains(formattedMessage, "[INFO]")
        assertContains(formattedMessage, "[TestTag]")
        assertContains(formattedMessage, "Test log message")
        assertContains(formattedMessage, "key1: value1")
        assertContains(formattedMessage, "key2: value2")
    }

}
package com.sphereon.oid.fed.logger

import co.touchlab.kermit.SimpleFormatter
import co.touchlab.kermit.platformLogWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity as KermitSeverity

/**
 * Enum defining the output format options for the logger.
 */
enum class LoggerOutputFormatEnum {
    TEXT,
    JSON
}

/**
 * Configuration options for the Logger.
 *
 * @property output The format in which logs should be output (TEXT or JSON).
 * @property severity The minimum severity level for logging.
 */
data class LoggerConfig(
    val output: LoggerOutputFormatEnum = LoggerOutputFormatEnum.TEXT,
    val severity: Logger.Severity = Logger.Severity.Info
)

/**
 * Logger class responsible for handling structured and configurable logging in the application.
 * Provides methods to log messages at various severity levels (Verbose, Debug, Info, Warn, Error, Assert).
 * Supports custom log formats, log writers, and a configurable logging mechanism.
 *
 * @constructor Creates a logger instance with a specific tag and configuration.
 *
 * @param tag A unique identifier for the source of the logs. Defaults to an empty string.
 * @param config Configuration object specifying output formatting and minimum severity level.
 */
class Logger internal constructor(
    private val tag: String = "",
    private val config: LoggerConfig = LoggerConfig()
) {
    enum class Severity {
        Verbose,
        Debug,
        Info,
        Warn,
        Error,
        Assert;

        internal fun toKermitSeverity(): KermitSeverity {
            return when (this) {
                Verbose -> KermitSeverity.Verbose
                Debug -> KermitSeverity.Debug
                Info -> KermitSeverity.Info
                Warn -> KermitSeverity.Warn
                Error -> KermitSeverity.Error
                Assert -> KermitSeverity.Assert
            }
        }
    }

    /**
     * Interface representing a mechanism for writing log events to a specific destination.
     *
     * Log writers implementing this interface are responsible for handling log events based on their specific
     * requirements, such as writing to files, databases, or external monitoring systems.
     */
    interface LogWriter {
        val minSeverity: Severity get() = Severity.Verbose
        fun log(event: LogEvent)
        fun close() {}
    }

    /**
     * Represents a JSON-serializable log event with various attributes for logging purposes.
     *
     * @property severity The severity level of the log (e.g., Verbose, Debug, Info, etc.).
     * @property message The log message providing details of the event.
     * @property tag A label used to categorize the log for easier identification.
     * @property timestamp The timestamp of when the log event occurred in milliseconds since the epoch.
     * @property exception An optional exception information containing its message and stacktrace.
     * @property metadata A map of additional key-value pairs providing context to the log event.
     */
    @Serializable
    data class LogEventJson(
        val severity: String,
        val message: String,
        val tag: String,
        val timestamp: Long,
        val exception: ExceptionInfo? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable
    data class ExceptionInfo(
        val message: String,
        val stacktrace: String
    )

    data class LogEvent(
        val severity: Severity,
        val message: String,
        val tag: String,
        val timestamp: Long,
        val throwable: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        val formattedMessage: String
            get() = buildString {
                append("[${severity.name.uppercase()}] ")
                append("${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())} ")
                if (tag.isNotBlank()) {
                    append("[$tag] ")
                }
                append(message)
                if (metadata.isNotEmpty()) {
                    append("\nContext:")
                    metadata.forEach { (key, value) ->
                        append("\n  $key: $value")
                    }
                }
                throwable?.let { t ->
                    append("\nException: ${t.message}")
                    append("\nStacktrace: ${t.stackTraceToString()}")
                }
            }

        /**
         * Converts the LogEvent to a JSON string representation.
         * @return A JSON string containing all LogEvent fields
         */
        fun toJson(): String {
            val exceptionInfo = throwable?.let { ExceptionInfo(it.message ?: "", it.stackTraceToString()) }
            val logEventJson = LogEventJson(
                severity = severity.name,
                message = message,
                tag = tag,
                timestamp = timestamp,
                exception = exceptionInfo,
                metadata = metadata
            )
            return Json.encodeToString(LogEventJson.serializer(), logEventJson)
        }
    }

    private val logger = KermitLogger.withTag(tag)

    /**
     * Determines whether logging should occur based on the provided severity level.
     *
     * @param severity The severity level of the log message.
     * @return True if the provided severity level is greater than or equal to the minimum
     *         severity level defined in the logger configuration; otherwise, false.
     */
    private fun shouldLog(severity: Severity): Boolean =
        severity.ordinal >= config.severity.ordinal

    /**
     * Builds a LogEvent from the provided parameters.
     *
     * @param severity The severity level.
     * @param message The log message.
     * @param tag The tag for the log.
     * @param throwable An optional throwable.
     * @param metadata Additional metadata.
     * @return A new LogEvent.
     */
    private fun buildLogEvent(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): LogEvent = LogEvent(
        severity = severity,
        message = message,
        tag = tag,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        throwable = throwable,
        metadata = metadata
    )

    private fun log(event: LogEvent) {
        val logMessage = when (config.output) {
            LoggerOutputFormatEnum.TEXT -> event.formattedMessage
            LoggerOutputFormatEnum.JSON -> event.toJson()
        }

        when (event.severity) {
            Severity.Verbose -> logger.v(logMessage)
            Severity.Debug -> logger.d(logMessage)
            Severity.Info -> logger.i(logMessage)
            Severity.Warn -> logger.w(logMessage)
            Severity.Error -> logger.e(logMessage)
            Severity.Assert -> logger.a(logMessage)
        }
        dispatchToLogWriters(event)
    }

    /**
     * Common logging function that does not log in case the severity is below the threshold.
     *
     * @param severity The severity level.
     * @param message The log message.
     * @param tag The log tag.
     * @param throwable An optional throwable.
     * @param metadata Additional metadata.
     */
    private fun logWithSeverity(
        severity: Severity,
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(severity)) return
        log(buildLogEvent(severity, message, tag, throwable, metadata))
    }

    fun verbose(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) = logWithSeverity(Severity.Verbose, message, tag, throwable, metadata)

    fun debug(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) = logWithSeverity(Severity.Debug, message, tag, throwable, metadata)

    fun info(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) = logWithSeverity(Severity.Info, message, tag, throwable, metadata)

    fun warn(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) = logWithSeverity(Severity.Warn, message, tag, throwable, metadata)

    fun error(
        message: String,
        throwable: Throwable? = null,
        tag: String = this.tag,
        metadata: Map<String, String> = emptyMap()
    ) = logWithSeverity(Severity.Error, message, tag, throwable, metadata)

    private fun dispatchToLogWriters(event: LogEvent) {
        registeredLogWriters
            .asSequence()
            .filter { writer -> event.severity.ordinal >= writer.minSeverity.ordinal }
            .forEach { writer -> writer.log(event) }
    }

    companion object {
        private val registeredLogWriters = mutableListOf<LogWriter>()
        private val loggerInstances = mutableMapOf<String, Logger>()
        private var defaultConfig: LoggerConfig = LoggerConfig()

        init {
            KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))
        }

        fun configure(config: LoggerConfig) {
            println("Configuring logger with severity: ${config.severity.name} and output format: ${config.output}")
            defaultConfig = config
            KermitLogger.setMinSeverity(config.severity.toKermitSeverity())
            KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))

            val existingTags = loggerInstances.keys.toList()
            loggerInstances.clear()
            existingTags.forEach { tag ->
                loggerInstances[tag] = Logger(tag, config)
            }
            println("Logger configuration complete. Current severity: ${config.severity.name}, output format: ${config.output}")
        }

        // For backward compatibility
        @Deprecated(
            "Use configure(LoggerConfig) instead",
            ReplaceWith("configure(LoggerConfig(severity = minSeverity, output = config.output))")
        )
        fun configure(minSeverity: Severity, config: LoggerConfig = defaultConfig) {
            configure(config.copy(severity = minSeverity))
        }

        fun addLogWriter(logWriter: LogWriter) {
            registeredLogWriters.add(logWriter)
        }

        fun tag(tag: String = "", config: LoggerConfig = defaultConfig): Logger {
            return loggerInstances.getOrPut(tag) { Logger(tag, config) }
        }

        fun close() {
            registeredLogWriters.forEach { writer ->
                try {
                    writer.close()
                } catch (e: Exception) {
                    KermitLogger.e("Error closing log writer: ${e.message}", e)
                }
            }
            registeredLogWriters.clear()
            loggerInstances.clear()
        }
    }
}

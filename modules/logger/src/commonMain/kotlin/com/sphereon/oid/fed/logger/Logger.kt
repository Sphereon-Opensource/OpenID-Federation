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
 */
data class LoggerConfig(
    val output: LoggerOutputFormatEnum = LoggerOutputFormatEnum.TEXT
)

class Logger internal constructor(private val tag: String = "", private val config: LoggerConfig = LoggerConfig()) {
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

    interface LogWriter {
        val minSeverity: Severity get() = Severity.Verbose
        fun log(event: LogEvent)
        fun close() {}
    }

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

    private fun shouldLog(severity: Severity): Boolean =
        severity.ordinal >= minSeverityLevel.ordinal

    private fun createLogEvent(
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

    fun verbose(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(Severity.Verbose)) return
        log(createLogEvent(Severity.Verbose, message, tag, throwable, metadata))
    }

    fun debug(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(Severity.Debug)) return
        log(createLogEvent(Severity.Debug, message, tag, throwable, metadata))
    }

    fun info(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(Severity.Info)) return
        log(createLogEvent(Severity.Info, message, tag, throwable, metadata))
    }

    fun warn(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(Severity.Warn)) return
        log(createLogEvent(Severity.Warn, message, tag, throwable, metadata))
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        tag: String = this.tag,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!shouldLog(Severity.Error)) return
        log(createLogEvent(Severity.Error, message, tag, throwable, metadata))
    }

    private fun dispatchToLogWriters(event: LogEvent) {
        registeredLogWriters
            .asSequence()
            .filter { writer -> event.severity.ordinal >= writer.minSeverity.ordinal }
            .forEach { writer -> writer.log(event) }
    }

    companion object {
        private var minSeverityLevel: Severity = Severity.Info
        private val registeredLogWriters = mutableListOf<LogWriter>()
        private val loggerInstances = mutableMapOf<String, Logger>()
        private var defaultConfig: LoggerConfig = LoggerConfig()

        init {
            KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))
        }

        fun configure(minSeverity: Severity, config: LoggerConfig = defaultConfig) {
            println("Configuring logger with severity: ${minSeverity.name} and output format: ${config.output}")
            minSeverityLevel = minSeverity
            defaultConfig = config
            KermitLogger.setMinSeverity(minSeverity.toKermitSeverity())
            KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))

            val existingTags = loggerInstances.keys.toList()
            loggerInstances.clear()
            existingTags.forEach { tag ->
                loggerInstances[tag] = Logger(tag, config)
            }
            println("Logger configuration complete. Current severity: ${minSeverityLevel.name}, output format: ${config.output}")
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

package com.sphereon.oid.fed.logger

import co.touchlab.kermit.SimpleFormatter
import co.touchlab.kermit.platformLogWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity as KermitSeverity

class Logger private constructor(private val tag: String = "") {
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
        /**
         * The minimum severity level this writer will handle.
         * Defaults to Verbose to handle all log events.
         */
        val minSeverity: Severity get() = Severity.Verbose

        /**
         * Log an event to this writer's destination
         */
        fun log(event: LogEvent)

        /**
         * Clean up any resources used by this writer.
         * This should be called when the writer is no longer needed.
         * Examples of cleanup:
         * - Closing file handles
         * - Closing network connections
         * - Flushing any buffered data
         */
        fun close() {}
    }

    data class LogEvent(
        val severity: Severity,
        val message: String,
        val tag: String,
        val timestamp: Long,
        val throwable: Throwable? = null,
        val metadata: Map<String, Any> = emptyMap()
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
                    append("\nContext: ")
                    metadata.forEach { (key, value) ->
                        append("\n  $key: $value")
                    }
                }
                throwable?.let { t ->
                    append("\nException: ${t.message}")
                    append("\nStacktrace: ${t.stackTraceToString()}")
                }
            }
    }

    private val logger = KermitLogger.withTag(tag)

    init {
        KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))
    }

    private fun shouldLog(severity: Severity): Boolean =
        severity.ordinal >= minSeverityLevel.ordinal

    private fun createLogEvent(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
        metadata: Map<String, Any>
    ): LogEvent = LogEvent(
        severity = severity,
        message = message,
        tag = tag,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        throwable = throwable,
        metadata = metadata
    )

    private fun log(event: LogEvent) {
        // Log to Kermit with appropriate severity
        when (event.severity) {
            Severity.Verbose -> logger.v(event.formattedMessage)
            Severity.Debug -> logger.d(event.formattedMessage)
            Severity.Info -> logger.i(event.formattedMessage)
            Severity.Warn -> logger.w(event.formattedMessage)
            Severity.Error -> logger.e(event.formattedMessage)
            Severity.Assert -> logger.a(event.formattedMessage)
        }
        dispatchToLogWriters(event)
    }

    fun verbose(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!shouldLog(Severity.Verbose)) return
        log(createLogEvent(Severity.Verbose, message, tag, throwable, context))
    }

    fun debug(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!shouldLog(Severity.Debug)) return
        log(createLogEvent(Severity.Debug, message, tag, throwable, context))
    }

    fun info(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!shouldLog(Severity.Info)) return
        log(createLogEvent(Severity.Info, message, tag, throwable, context))
    }

    fun warn(
        message: String,
        tag: String = this.tag,
        throwable: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!shouldLog(Severity.Warn)) return
        log(createLogEvent(Severity.Warn, message, tag, throwable, context))
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        tag: String = this.tag,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!shouldLog(Severity.Error)) return
        log(createLogEvent(Severity.Error, message, tag, throwable, context))
    }

    private fun dispatchToLogWriters(event: LogEvent) {
        // Dispatch to compatible writers
        registeredLogWriters
            .asSequence()
            .filter { writer -> event.severity.ordinal >= writer.minSeverity.ordinal }
            .forEach { writer -> writer.log(event) }

        // Then log debug information if enabled
        if (minSeverityLevel <= Severity.Debug) {
            dispatchDebugInfo(event)
        }
    }

    private fun dispatchDebugInfo(originalEvent: LogEvent) {
        val debugEvent = createDebugEvent(originalEvent)
        logger.d(debugEvent.formattedMessage)

        // Dispatch debug info to writers that accept debug level
        registeredLogWriters
            .asSequence()
            .filter { writer -> Severity.Debug.ordinal >= writer.minSeverity.ordinal }
            .forEach { writer -> writer.log(debugEvent) }
    }

    private fun createDebugEvent(originalEvent: LogEvent): LogEvent {
        return LogEvent(
            severity = Severity.Debug,
            message = buildDebugMessage(originalEvent),
            tag = originalEvent.tag,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
    }

    private fun buildDebugMessage(event: LogEvent): String = buildString {
        append("Log Dispatch Debug Info:")
        append("\n  Total writers: ${registeredLogWriters.size}")
        append("\n  Message severity: ${event.severity} (ordinal: ${event.severity.ordinal})")
        if (event.throwable != null) {
            append("\n  Exception: ${event.throwable.message}")
        }
        append("\n  Writers handling message:")
        registeredLogWriters.forEach { writer ->
            append("\n    - Writer ${writer::class.simpleName}: ")
            append("minSeverity=${writer.minSeverity} (ordinal: ${writer.minSeverity.ordinal}) - ")
            append(if (event.severity.ordinal >= writer.minSeverity.ordinal) "WILL HANDLE" else "SKIPPED")
        }
    }

    companion object {
        private var minSeverityLevel: Severity = Severity.Info
        private val registeredLogWriters = mutableListOf<LogWriter>()

        fun configure(minSeverity: Severity) {
            minSeverityLevel = minSeverity
            KermitLogger.setMinSeverity(minSeverity.toKermitSeverity())
        }

        fun addLogWriter(logWriter: LogWriter) {
            registeredLogWriters.add(logWriter)
        }

        fun tag(tag: String = "") = Logger(tag)

        fun close() {
            registeredLogWriters.forEach { writer ->
                try {
                    writer.close()
                } catch (e: Exception) {
                    // Log the error but continue closing other writers
                    KermitLogger.e("Error closing log writer: ${e.message}", e)
                }
            }
            registeredLogWriters.clear()
        }
    }
}
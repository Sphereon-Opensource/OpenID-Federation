package com.sphereon.oid.fed.logger

import co.touchlab.kermit.SimpleFormatter
import co.touchlab.kermit.platformLogWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity as KermitSeverity

interface LogWriter {
    fun log(message: String)
}

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

data class LoggerEvent(
    val severity: Severity,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
) {
    val formattedMessage: String
        get() = buildString {
            append(message)
            throwable?.let { t ->
                append("\nException: ${t.message}")
                append("\nStacktrace: ${t.stackTraceToString()}")
            }
        }

    val logPrefix: String
        get() = "($tag)"
}

class Logger(private val tag: String = "") {
    private val logger = KermitLogger.withTag(tag)

    init {
        KermitLogger.setLogWriters(platformLogWriter(SimpleFormatter))
    }

    fun verbose(message: String, tag: String = this.tag, throwable: Throwable? = null) {
        val formattedMessage = buildLogMessage(Severity.Verbose, message, tag, throwable)
        logger.v(formattedMessage)
        dispatchToLogWriters(formattedMessage)
    }

    fun debug(message: String, tag: String = this.tag, throwable: Throwable? = null) {
        val formattedMessage = buildLogMessage(Severity.Debug, message, tag, throwable)
        logger.d(formattedMessage)
        dispatchToLogWriters(formattedMessage)
    }

    fun info(message: String, tag: String = this.tag, throwable: Throwable? = null) {
        val formattedMessage = buildLogMessage(Severity.Info, message, tag, throwable)
        logger.i(formattedMessage)
        dispatchToLogWriters(formattedMessage)
    }

    fun warn(message: String, tag: String = this.tag, throwable: Throwable? = null) {
        val formattedMessage = buildLogMessage(Severity.Warn, message, tag, throwable)
        logger.w(formattedMessage)
        dispatchToLogWriters(formattedMessage)
    }

    fun error(message: String, throwable: Throwable? = null, tag: String = this.tag) {
        val formattedMessage = buildLogMessage(Severity.Error, message, tag, throwable)
        logger.e(formattedMessage)
        dispatchToLogWriters(formattedMessage)
    }

    private fun buildLogMessage(
        severity: Severity,
        message: Any,
        tag: String,
        throwable: Throwable? = null
    ): String {
        return buildString {
            append("[${severity.name.uppercase()}] ")
            append("${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())} ")
            if (tag.isNotBlank()) {
                append("[$tag] ")
            }
            append(message.toString())
            throwable?.let { t ->
                append("\nException: ${t.message}")
                append("\nStacktrace: ${t.stackTraceToString()}")
            }
        }
    }

    private fun dispatchToLogWriters(message: String) {
        registeredLogWriters.forEach { writer ->
            writer.log(message)
        }
    }

    companion object {
        private var defaultMinSeverity: Severity = Severity.Info
        private val registeredLogWriters = mutableListOf<LogWriter>()

        fun configure(minSeverity: Severity) {
            KermitLogger.setMinSeverity(minSeverity.toKermitSeverity())
        }

        fun addLogWriter(logWriter: LogWriter) {
            registeredLogWriters.add(logWriter)
        }

        fun tag(tag: String = "") = Logger(tag)
    }
}

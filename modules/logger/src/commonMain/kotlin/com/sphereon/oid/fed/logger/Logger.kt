package com.sphereon.oid.fed.logger

import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity as KermitSeverity

enum class Severity {
    Verbose,
    Debug,
    Info,
    Warn,
    Error;

    internal fun toKermitSeverity(): KermitSeverity {
        return when (this) {
            Verbose -> KermitSeverity.Verbose
            Debug -> KermitSeverity.Debug
            Info -> KermitSeverity.Info
            Warn -> KermitSeverity.Warn
            Error -> KermitSeverity.Error
        }
    }
}

class Logger(val tag: String = "") {
    fun verbose(message: String, tag: String = this.tag) {
        KermitLogger.v(tag = tag, messageString = message)
    }

    fun debug(message: String, tag: String = this.tag) {
        KermitLogger.d(tag = tag, messageString = message)
    }

    fun info(message: String, tag: String = this.tag) {
        KermitLogger.i(tag = tag, messageString = message)
    }

    fun warn(message: String, tag: String = this.tag) {
        KermitLogger.w(tag = tag, messageString = message)
    }

    fun error(message: String, throwable: Throwable? = null, tag: String = this.tag) {
        KermitLogger.e(tag = tag, messageString = message, throwable = throwable)
    }

    fun setMinSeverity(severity: Severity) = KermitLogger.setMinSeverity(severity.toKermitSeverity())

    companion object {
        private var defaultMinSeverity: Severity = Severity.Debug

        fun configure(minSeverity: Severity) {
            defaultMinSeverity = minSeverity
        }

        fun tag(tag: String = "") = Logger(tag).also { it.setMinSeverity(defaultMinSeverity) }
    }
}

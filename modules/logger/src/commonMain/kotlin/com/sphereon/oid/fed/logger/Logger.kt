package com.sphereon.oid.fed.logger

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

class Logger(val tag: String = "") {
    fun verbose(message: String, tag: String = this.tag) {
        Logger.v(tag = tag, messageString = message)
    }

    fun debug(message: String, tag: String = this.tag) {
        Logger.d(tag = tag, messageString = message)
    }

    fun info(message: String, tag: String = this.tag) {
        Logger.i(tag = tag, messageString = message)
    }

    fun warn(message: String, tag: String = this.tag) {
        Logger.w(tag = tag, messageString = message)
    }

    fun error(message: String, throwable: Throwable? = null, tag: String = this.tag) {
        Logger.e(tag = tag, messageString = message, throwable = throwable)
    }

    fun setMinSeverity(severity: Severity) = Logger.setMinSeverity(severity)

    object Static {
        fun tag(tag: String = "", severity: Severity = Severity.Info) = Logger(tag).also { it.setMinSeverity(severity) }
    }
}

val DefaultLogger = Logger("")

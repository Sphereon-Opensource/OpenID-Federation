package com.sphereon.oid.fed.common.logging

import co.touchlab.kermit.Logger

object Logger {

    fun verbose(tag: String, message: String) {
        Logger.v(tag = tag, messageString = message)
    }

    fun debug(tag: String, message: String) {
        Logger.d(tag = tag, messageString = message)
    }

    fun info(tag: String, message: String) {
        Logger.i(tag = tag, messageString = message)
    }

    fun warn(tag: String, message: String) {
        Logger.w(tag = tag, messageString = message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Logger.e(tag = tag, messageString = message, throwable = throwable)
    }
}
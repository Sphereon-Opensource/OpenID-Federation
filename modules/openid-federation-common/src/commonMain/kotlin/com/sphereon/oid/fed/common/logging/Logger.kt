package com.sphereon.oid.fed.common.logging

import co.touchlab.kermit.Logger
//import io.github.cdimascio.dotenv.dotenv

object Logger {

//    val dotenv = dotenv()
//    val isLogging = dotenv["LOGGING"]



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
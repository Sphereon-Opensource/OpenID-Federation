package com.sphereon.oid.fed.server.admin.handlers

import com.sphereon.oid.fed.logger.LogWriter
import java.io.File

class FileLoggerHandler(private val logFile: File) : LogWriter {
    init {
        try {
            logFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IllegalStateException("Failed to create log directory: ${parent.absolutePath}")
                }
            }
            if (!logFile.exists() && !logFile.createNewFile()) {
                throw IllegalStateException("Failed to create log file: ${logFile.absolutePath}")
            }
            if (!logFile.canWrite()) {
                throw IllegalStateException("Log file is not writable: ${logFile.absolutePath}")
            }
        } catch (e: SecurityException) {
            throw IllegalStateException("Security violation while setting up log file: ${logFile.absolutePath}", e)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize log file: ${logFile.absolutePath}", e)
        }
    }

    override fun log(message: String) {
        synchronized(this) {
            logFile.appendText("$message\n")
        }
    }
}

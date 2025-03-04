package com.sphereon.oid.fed.server.admin.handlers.logger

import com.sphereon.oid.fed.logger.Logger
import java.io.File
import java.nio.file.Files

class FileLoggerHandler(private val logFile: File) : Logger.LogWriter {
    init {
        try {
            println("Attempting to initialize log file at: ${logFile.absolutePath}")

            logFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    println("Log directory doesn't exist, attempting to create: ${parent.absolutePath}")
                    try {
                        Files.createDirectories(parent.toPath())
                        println("Successfully created log directory")
                    } catch (e: Exception) {
                        val msg = "Failed to create log directory: ${parent.absolutePath}. Error: ${e.message}"
                        println(msg)
                        throw IllegalStateException(msg, e)
                    }
                }

                // Check directory permissions
                if (!parent.canWrite()) {
                    val msg = "No write permission for log directory: ${parent.absolutePath}"
                    println(msg)
                    throw IllegalStateException(msg)
                }
            }

            if (!logFile.exists()) {
                println("Log file doesn't exist, attempting to create: ${logFile.absolutePath}")
                try {
                    logFile.createNewFile()
                    println("Successfully created log file")
                } catch (e: Exception) {
                    val msg = "Failed to create log file: ${logFile.absolutePath}. Error: ${e.message}"
                    println(msg)
                    throw IllegalStateException(msg, e)
                }
            }

            if (!logFile.canWrite()) {
                val msg = "Log file is not writable: ${logFile.absolutePath}"
                println(msg)
                throw IllegalStateException(msg)
            }

            println("Successfully initialized log file handler")
        } catch (e: SecurityException) {
            val msg = "Security violation while setting up log file: ${logFile.absolutePath}"
            println("$msg. Error: ${e.message}")
            throw IllegalStateException(msg, e)
        } catch (e: Exception) {
            val msg = "Failed to initialize log file: ${logFile.absolutePath}"
            println("$msg. Error: ${e.message}")
            throw IllegalStateException(msg, e)
        }
    }

    override fun log(event: Logger.LogEvent) {
        synchronized(this) {
            try {
                logFile.appendText("${event.toJson()}\n")
            } catch (e: Exception) {
                println("Failed to write to log file: ${logFile.absolutePath}. Error: ${e.message}")
                // Consider implementing a fallback logging mechanism here
            }
        }
    }
}

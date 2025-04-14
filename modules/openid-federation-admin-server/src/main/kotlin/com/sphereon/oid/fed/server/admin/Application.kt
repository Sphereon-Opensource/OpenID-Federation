package com.sphereon.oid.fed.server.admin

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.logger.LoggerConfig
import com.sphereon.oid.fed.logger.LoggerOutputFormatEnum
import com.sphereon.oid.fed.server.admin.handlers.logger.DatabaseLoggerHandler
import com.sphereon.oid.fed.server.admin.handlers.logger.FileLoggerHandler
import com.sphereon.oid.fed.services.LogService
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@SpringBootApplication
@EnableScheduling
class Application(
    private val logService: LogService,
    private val environment: Environment,
) {
    @Value("\${sphereon.logger.severity:Info}")
    private var severity: String = Logger.Severity.Info.name

    @PostConstruct
    fun configureLogger() {
        val logDir = File("/tmp/logs").apply { mkdirs() }
        val logFile =
            File(logDir, "federation-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}.log")

        // Read severity from properties
        val severity = try {
            Logger.Severity.valueOf(severity)
        } catch (e: IllegalArgumentException) {
            println("Failed to parse severity '$severity', defaulting to Verbose. Error: ${e.message}")
            Logger.Severity.Verbose
        }

        // Read output format from properties
        val outputFormatStr = environment.getProperty("sphereon.logger.output", "TEXT")
        println("Read output format from environment: '$outputFormatStr'")
        val outputFormat = try {
            LoggerOutputFormatEnum.valueOf(outputFormatStr)
        } catch (e: IllegalArgumentException) {
            println("Failed to parse output format '$outputFormatStr', defaulting to TEXT. Error: ${e.message}")
            LoggerOutputFormatEnum.TEXT
        }

        println("Final configured severity: ${severity.name}, output format: ${outputFormat.name}")

        // Create logger config with the specified severity and output format
        val loggerConfig = LoggerConfig(
            severity = severity,
            output = outputFormat
        )

        // Configure the logger with the single config object
        Logger.configure(loggerConfig)

        // Add log writers with the same configuration
        Logger.addLogWriter(FileLoggerHandler(logFile, loggerConfig))
        Logger.addLogWriter(DatabaseLoggerHandler(logService))
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

package com.sphereon.oid.fed.server.admin

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.server.admin.handlers.logger.DatabaseLoggerHandler
import com.sphereon.oid.fed.server.admin.handlers.logger.FileLoggerHandler
import com.sphereon.oid.fed.services.LogService
import jakarta.annotation.PostConstruct
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
    @PostConstruct
    fun configureLogger() {
        val logDir = File("/tmp/logs").apply { mkdirs() }
        val logFile =
            File(logDir, "federation-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}.log")

        val severityStr = environment.getProperty("sphereon.logger.severity", "Info")
        println("Read severity from environment: '$severityStr'")
        val severity = try {
            Logger.Severity.valueOf(severityStr)
        } catch (e: IllegalArgumentException) {
            println("Failed to parse severity '$severityStr', defaulting to Verbose. Error: ${e.message}")
            Logger.Severity.Verbose
        }
        println("Final configured severity: ${severity.name}")
        Logger.configure(minSeverity = severity)

        Logger.addLogWriter(FileLoggerHandler(logFile))
        Logger.addLogWriter(DatabaseLoggerHandler(logService))
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

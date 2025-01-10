package com.sphereon.oid.fed.server.admin

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.logger.Severity
import com.sphereon.oid.fed.server.admin.handlers.DatabaseLoggerHandler
import com.sphereon.oid.fed.server.admin.handlers.FileLoggerHandler
import com.sphereon.oid.fed.services.LogService
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@SpringBootApplication
@EnableScheduling
class Application(private val logService: LogService) {
    @PostConstruct
    fun configureLogger() {
        val logDir = File("logs").apply { mkdirs() }
        val logFile =
            File(logDir, "federation-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}.log")

        Logger.addLogWriter(FileLoggerHandler(logFile))
        Logger.addLogWriter(DatabaseLoggerHandler(logService))

        Logger.configure(Severity.Verbose)
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

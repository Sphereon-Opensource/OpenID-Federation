package com.sphereon.oid.fed.server.admin.handlers.logger

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.services.LogService

class DatabaseLoggerHandler(private val logService: LogService) : Logger.LogWriter {
    override fun log(event: Logger.LogEvent) {
        logService.insertLog(
            severity = event.severity.name,
            message = event.message,
            tag = event.tag,
            timestamp = event.timestamp,
            throwable = event.throwable,
            metadata = event.metadata
        )
    }
}
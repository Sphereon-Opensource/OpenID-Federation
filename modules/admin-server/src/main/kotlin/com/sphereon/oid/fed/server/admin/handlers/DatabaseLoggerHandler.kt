package com.sphereon.oid.fed.server.admin.handlers

import com.sphereon.oid.fed.logger.LogWriter
import com.sphereon.oid.fed.services.LogService

class DatabaseLoggerHandler(private val logService: LogService) : LogWriter {
    override fun log(message: String) {
        logService.insertLog(message)
    }
}

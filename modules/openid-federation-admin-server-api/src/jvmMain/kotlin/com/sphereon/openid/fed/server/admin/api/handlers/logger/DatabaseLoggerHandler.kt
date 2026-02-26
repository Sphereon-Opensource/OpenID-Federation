package com.sphereon.openid.fed.server.admin.api.handlers.logger

import com.sphereon.openid.fed.services.LogService

// TODO: Re-implement using IDK logging infrastructure
// This handler previously implemented Logger.LogWriter from the old OIDF Logger,
// which wrote log events to the database via LogService.
// The IDK logging system uses a different architecture and this needs to be
// redesigned to integrate with the new logging infrastructure.

/*
class DatabaseLoggerHandler(private val logService: LogService) : Logger.LogWriter {
    override fun log(event: Logger.LogEvent) {
        logService.insertLog(
            severity = event.severity,
            message = event.message,
            tag = event.tag,
            timestamp = event.timestamp,
            throwable = event.throwable,
            metadata = event.metadata
        )
    }
}
*/

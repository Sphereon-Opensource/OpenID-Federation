package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.logger.LoggerEvent
import com.sphereon.oid.fed.persistence.models.LogQueries

open class LogService(private val logQueries: LogQueries) {
    fun insertLog(message: String) {
        logQueries.insertLog(message)
    }

    fun insertLog(event: LoggerEvent) {
        // For backwards compatibility, use the formatted message
        insertLog(event.formattedMessage)
    }

    fun getRecentLogs(limit: Long = 100L) = logQueries.getRecentLogs(limit).executeAsList()

    fun searchLogs(searchTerm: String, limit: Long = 100L) =
        logQueries.searchLogs(searchTerm, limit).executeAsList()
}

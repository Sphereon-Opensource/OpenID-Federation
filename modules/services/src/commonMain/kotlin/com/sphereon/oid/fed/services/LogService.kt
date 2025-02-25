package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.Log
import com.sphereon.oid.fed.persistence.models.LogQueries
import com.sphereon.oid.fed.services.mappers.log.toDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

open class LogService(private val logQueries: LogQueries) {
    fun insertLog(
        severity: String,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ) {
        logQueries.insertLog(
            severity = severity,
            message = message,
            tag = tag,
            timestamp = timestamp,
            throwable_message = throwable?.message,
            throwable_stacktrace = throwable?.stackTraceToString(),
            metadata = if (metadata.isNotEmpty()) Json.encodeToString(metadata) else null
        )
    }

    fun getRecentLogs(limit: Long = 100L) = logQueries.getRecentLogs(limit).executeAsList().map { it.toDTO() }

    fun searchLogs(searchTerm: String, limit: Long = 100L): List<Log> =
        logQueries.searchLogs(searchTerm, limit).executeAsList().map { it.toDTO() }

    fun getLogsBySeverity(severity: String, limit: Long = 100L): List<Log> =
        logQueries.getLogsBySeverity(severity, limit).executeAsList().map { it.toDTO() }

    fun getLogsByTag(tag: String, limit: Long = 100L): List<Log> =
        logQueries.getLogsByTag(tag, limit).executeAsList().map { it.toDTO() }
}
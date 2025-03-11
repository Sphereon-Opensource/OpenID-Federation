package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Log
import com.sphereon.oid.fed.persistence.models.LogQueries
import com.sphereon.oid.fed.services.mappers.log.toDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provides services to manage logging operations, including inserting logs,
 * retrieving recent logs, and searchable queries.
 *
 * This class encapsulates interactions with the database layer for logging purposes
 * and transforms raw log data into domain-specific objects.
 */
open class LogService(private val logQueries: LogQueries) {
    /**
     * Inserts a log entry into the log storage system.
     *
     * @param severity The severity level of the log.
     * @param message The log message to be recorded.
     * @param tag A tag to categorize or identify the source of the log.
     * @param timestamp The timestamp of the log occurrence in milliseconds since epoch.
     * @param throwable An optional throwable object representing an exception or error.
     * @param metadata Additional metadata associated with the log entry, represented as key-value pairs.
     */
    fun insertLog(
        severity: Logger.Severity,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ) {
        logQueries.insertLog(
            severity = severity.name,
            message = message,
            tag = tag,
            timestamp = timestamp,
            throwable_message = throwable?.message,
            throwable_stacktrace = throwable?.stackTraceToString(),
            metadata = if (metadata.isNotEmpty()) Json.encodeToString(metadata) else null
        )
    }

    /**
     * Retrieves a list of recent log entries from the database, sorted in descending order by timestamp.
     *
     * @param limit The maximum number of log entries to retrieve. Defaults to 100 if not specified.
     * @return A list of logs converted to a DTO object format.
     */
    fun getRecentLogs(limit: Long = 100L) = logQueries.getRecentLogs(limit).executeAsList().map { it.toDTO() }

    /**
     * Searches for logs that match the provided search term.
     *
     * @param searchTerm The term to search for within log entries.
     * @param limit The maximum number of logs to return. Defaults to 100.
     * @return A list of log entries that match the specified search term and meet the defined constraints.
     */
    fun searchLogs(searchTerm: String, limit: Long = 100L): List<Log> =
        logQueries.searchLogs(searchTerm, limit).executeAsList().map { it.toDTO() }

    /**
     * Retrieves a list of logs filtered by the specified severity level.
     *
     * @param severity The severity level to filter logs by (e.g., "Error", "Warn").
     * @param limit The maximum number of logs to retrieve. Defaults to 100.
     * @return A list of logs that match the specified severity level, converted to the `Log` data type.
     */
    fun getLogsBySeverity(severity: String, limit: Long = 100L): List<Log> =
        logQueries.getLogsBySeverity(severity, limit).executeAsList().map { it.toDTO() }

    /**
     * Retrieves a list of logs associated with a specific tag.
     *
     * @param tag The tag/category used to filter log entries.
     * @param limit The maximum number of log entries to retrieve. Defaults to 100.
     * @return A list of logs matching the specified tag, sorted by timestamp in descending order.
     */
    fun getLogsByTag(tag: String, limit: Long = 100L): List<Log> =
        logQueries.getLogsByTag(tag, limit).executeAsList().map { it.toDTO() }
}

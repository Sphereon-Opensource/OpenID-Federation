package com.sphereon.openid.fed.services

import com.sphereon.core.api.log.LogLevel
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Log

/**
 * Service interface for managing logging operations, including inserting logs,
 * retrieving recent logs, and searchable queries.
 *
 * This service aggregates all log-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface LogService {

    // Convenience methods that delegate to command services

    /**
     * Inserts a log entry into the log storage system.
     *
     * @param level The log level (severity) of the log.
     * @param message The log message to be recorded.
     * @param tag A tag to categorize or identify the source of the log.
     * @param timestamp The timestamp of the log occurrence in milliseconds since epoch.
     * @param throwable An optional throwable object representing an exception or error.
     * @param metadata Additional metadata associated with the log entry.
     * @return FederationResult containing Unit on success or an error.
     */
    suspend fun insertLog(
        level: LogLevel,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): FederationResult<Unit>

    /**
     * Retrieves a list of recent log entries from the database.
     *
     * @param limit The maximum number of log entries to retrieve. Defaults to 100.
     * @return FederationResult containing a list of logs or an error.
     */
    suspend fun getRecentLogs(limit: Long): FederationResult<List<Log>>

    /**
     * Searches for logs that match the provided search term.
     *
     * @param searchTerm The term to search for within log entries.
     * @param limit The maximum number of logs to return. Defaults to 100.
     * @return FederationResult containing a list of log entries or an error.
     */
    suspend fun searchLogs(searchTerm: String, limit: Long): FederationResult<List<Log>>

    /**
     * Retrieves a list of logs filtered by the specified severity level.
     *
     * @param severity The severity level to filter logs by.
     * @param limit The maximum number of logs to retrieve. Defaults to 100.
     * @return FederationResult containing a list of logs or an error.
     */
    suspend fun getLogsBySeverity(severity: String, limit: Long): FederationResult<List<Log>>

    /**
     * Retrieves a list of logs associated with a specific tag.
     *
     * @param tag The tag/category used to filter log entries.
     * @param limit The maximum number of log entries to retrieve. Defaults to 100.
     * @return FederationResult containing a list of logs or an error.
     */
    suspend fun getLogsByTag(tag: String, limit: Long): FederationResult<List<Log>>
}

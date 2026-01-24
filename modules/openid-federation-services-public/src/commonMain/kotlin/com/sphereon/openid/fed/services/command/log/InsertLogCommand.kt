package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.log.LogLevel
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError

/**
 * Arguments for the InsertLog command.
 */
data class InsertLogArgs(
    val level: LogLevel,
    val message: String,
    val tag: String,
    val timestamp: Long,
    val throwable: Throwable?,
    val metadata: Map<String, String>
)

/**
 * Service interface for insert log operation.
 */
interface InsertLogCommandService {
    /**
     * Inserts a log entry into the log storage system.
     *
     * @param level The log level (severity) of the log.
     * @param message The log message to be recorded.
     * @param tag A tag to categorize or identify the source of the log.
     * @param timestamp The timestamp of the log occurrence in milliseconds since epoch.
     * @param throwable An optional throwable object representing an exception or error.
     * @param metadata Additional metadata associated with the log entry.
     * @return IdkResult containing Unit on success or an error.
     */
    suspend fun insertLog(
        level: LogLevel,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): IdkResult<Unit, FederationError>
}

/**
 * Command to insert a log entry.
 */
interface InsertLogCommand : Command<InsertLogArgs, Unit, FederationError>, InsertLogCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.log.insert"
    }
}

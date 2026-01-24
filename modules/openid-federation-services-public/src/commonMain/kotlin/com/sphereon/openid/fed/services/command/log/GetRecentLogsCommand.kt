package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Log

/**
 * Arguments for the GetRecentLogs command.
 */
data class GetRecentLogsArgs(
    val limit: Long = 100L
)

/**
 * Service interface for get recent logs operation.
 */
interface GetRecentLogsCommandService {
    /**
     * Retrieves a list of recent log entries from the database.
     *
     * @param limit The maximum number of log entries to retrieve. Defaults to 100.
     * @return IdkResult containing a list of logs or an error.
     */
    suspend fun getRecentLogs(limit: Long = 100L): IdkResult<List<Log>, FederationError>
}

/**
 * Command to get recent log entries.
 */
interface GetRecentLogsCommand : Command<GetRecentLogsArgs, List<Log>, FederationError>, GetRecentLogsCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.log.get-recent"
    }
}

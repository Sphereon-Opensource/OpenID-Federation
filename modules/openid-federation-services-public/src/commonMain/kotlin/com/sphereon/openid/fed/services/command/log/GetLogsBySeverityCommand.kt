package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Log

/**
 * Arguments for the GetLogsBySeverity command.
 */
data class GetLogsBySeverityArgs(
    val severity: String,
    val limit: Long = 100L
)

/**
 * Service interface for get logs by severity operation.
 */
interface GetLogsBySeverityCommandService {
    /**
     * Retrieves a list of logs filtered by the specified severity level.
     *
     * @param severity The severity level to filter logs by.
     * @param limit The maximum number of logs to retrieve. Defaults to 100.
     * @return IdkResult containing a list of logs or an error.
     */
    suspend fun getLogsBySeverity(severity: String, limit: Long = 100L): IdkResult<List<Log>, FederationError>
}

/**
 * Command to get log entries filtered by severity level.
 */
interface GetLogsBySeverityCommand : Command<GetLogsBySeverityArgs, List<Log>, FederationError>, GetLogsBySeverityCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.log.get-by-severity"
    }
}

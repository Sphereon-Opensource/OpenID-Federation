package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Log

/**
 * Arguments for the SearchLogs command.
 */
data class SearchLogsArgs(
    val searchTerm: String,
    val limit: Long = 100L
)

/**
 * Service interface for search logs operation.
 */
interface SearchLogsCommandService {
    /**
     * Searches for logs that match the provided search term.
     *
     * @param searchTerm The term to search for within log entries.
     * @param limit The maximum number of logs to return. Defaults to 100.
     * @return IdkResult containing a list of log entries or an error.
     */
    suspend fun searchLogs(searchTerm: String, limit: Long = 100L): IdkResult<List<Log>, FederationError>
}

/**
 * Command to search for log entries.
 */
interface SearchLogsCommand : Command<SearchLogsArgs, List<Log>, FederationError>, SearchLogsCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.log.search"
    }
}

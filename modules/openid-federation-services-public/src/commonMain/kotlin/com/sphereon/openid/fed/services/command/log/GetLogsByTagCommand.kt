package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Log

/**
 * Arguments for the GetLogsByTag command.
 */
data class GetLogsByTagArgs(
    val tag: String,
    val limit: Long = 100L
)

/**
 * Service interface for get logs by tag operation.
 */
interface GetLogsByTagCommandService {
    /**
     * Retrieves a list of logs associated with a specific tag.
     *
     * @param tag The tag/category used to filter log entries.
     * @param limit The maximum number of log entries to retrieve. Defaults to 100.
     * @return IdkResult containing a list of logs or an error.
     */
    suspend fun getLogsByTag(tag: String, limit: Long = 100L): IdkResult<List<Log>, FederationError>
}

/**
 * Command to get log entries filtered by tag.
 */
interface GetLogsByTagCommand : Command<GetLogsByTagArgs, List<Log>, FederationError>, GetLogsByTagCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.log.get-by-tag"
    }
}

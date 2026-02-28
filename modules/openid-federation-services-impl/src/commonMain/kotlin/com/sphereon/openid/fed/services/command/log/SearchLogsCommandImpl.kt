package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.openapi.models.Log as LogDTO

/**
 * Implementation of the SearchLogsCommand.
 * Searches for log entries matching a search term.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = SearchLogsCommand::class)
class SearchLogsCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<SearchLogsArgs, List<LogDTO>>(
    commandId = SearchLogsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SearchLogsArgs>(),
    outputTypeToken = typeToken<List<LogDTO>>()
), SearchLogsCommand {

    private val logger = Log.app().withTag("SearchLogsCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun doExecute(
        args: SearchLogsArgs,
        applyDuring: (SearchLogsArgs) -> SearchLogsArgs
    ): IdkResult<List<LogDTO>, IdkError> {
        val (searchTerm, limit) = applyDuring(args)

        logger.debug("Searching logs with term: '$searchTerm', limit: $limit")

        return try {
            val logs = logQueries.searchLogs(searchTerm, limit).executeAsList().map { it.toDTO() }
            logger.debug("Found ${logs.size} logs matching search term: '$searchTerm'")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to search logs with term: '$searchTerm'", e)
            federationErr(ServerError("Failed to search logs", e.message, e))
        }
    }
}

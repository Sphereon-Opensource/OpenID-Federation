package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.openapi.models.Log as LogDTO

/**
 * Implementation of the GetRecentLogsCommand.
 * Retrieves recent log entries from the database.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetRecentLogsCommand::class)
class GetRecentLogsCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetRecentLogsArgs, List<LogDTO>, FederationError>(
    id = GetRecentLogsCommand.COMMAND_ID,
    execution = execution
), GetRecentLogsCommand {

    private val logger = Log.app().withTag("GetRecentLogsCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun getRecentLogs(limit: Long): IdkResult<List<LogDTO>, FederationError> {
        return execute(GetRecentLogsArgs(limit), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: GetRecentLogsArgs,
        sessionContext: SessionContext,
        applyDuring: (GetRecentLogsArgs) -> GetRecentLogsArgs
    ): IdkResult<List<LogDTO>, FederationError> {
        val (limit) = applyDuring(args)

        logger.debug("Retrieving recent logs with limit: $limit")

        return try {
            val logs = logQueries.getRecentLogs(limit).executeAsList().map { it.toDTO() }
            logger.debug("Retrieved ${logs.size} recent logs")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to retrieve recent logs", e)
            IdkResult.err(ServerError("Failed to retrieve recent logs", e.message, e))
        }
    }
}

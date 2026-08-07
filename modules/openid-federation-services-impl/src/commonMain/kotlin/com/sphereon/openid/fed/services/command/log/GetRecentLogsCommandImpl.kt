package com.sphereon.openid.fed.services.command.log

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.openapi.models.Log as LogDTO

/**
 * Implementation of the GetRecentLogsCommand.
 * Retrieves recent log entries from the database.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetRecentLogsCommand>())
class GetRecentLogsCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetRecentLogsArgs, List<LogDTO>, FederationError>(
    commandId = GetRecentLogsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetRecentLogsArgs>(),
    outputTypeToken = typeToken<List<LogDTO>>()
), GetRecentLogsCommand {

    private val logger = execution.federationLogger("GetRecentLogsCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun doExecute(
        args: GetRecentLogsArgs,
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
            federationErr(ServerError("Failed to retrieve recent logs", e.message, e))
        }
    }
}

package com.sphereon.openid.fed.services.command.log

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
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
 * Implementation of the GetLogsByTagCommand.
 * Retrieves log entries filtered by tag.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetLogsByTagCommand::class)
class GetLogsByTagCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetLogsByTagArgs, List<LogDTO>, FederationError>(
    id = GetLogsByTagCommand.COMMAND_ID,
    execution = execution
), GetLogsByTagCommand {

    private val logger = Log.app().withTag("GetLogsByTagCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun getLogsByTag(tag: String, limit: Long): IdkResult<List<LogDTO>, FederationError> {
        return execute(GetLogsByTagArgs(tag, limit))
    }

    override suspend fun doExecute(
        args: GetLogsByTagArgs,
        applyDuring: (GetLogsByTagArgs) -> GetLogsByTagArgs
    ): IdkResult<List<LogDTO>, FederationError> {
        val (tag, limit) = applyDuring(args)

        logger.debug("Retrieving logs by tag: '$tag', limit: $limit")

        return try {
            val logs = logQueries.getLogsByTag(tag, limit).executeAsList().map { it.toDTO() }
            logger.debug("Retrieved ${logs.size} logs with tag: '$tag'")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to retrieve logs by tag: '$tag'", e)
            IdkResult.err(ServerError("Failed to retrieve logs by tag", e.message, e))
        }
    }
}

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
 * Implementation of the GetLogsBySeverityCommand.
 * Retrieves log entries filtered by severity level.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetLogsBySeverityCommand::class)
class GetLogsBySeverityCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetLogsBySeverityArgs, List<LogDTO>, FederationError>(
    id = GetLogsBySeverityCommand.COMMAND_ID,
    execution = execution
), GetLogsBySeverityCommand {

    private val logger = Log.app().withTag("GetLogsBySeverityCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun getLogsBySeverity(severity: String, limit: Long): IdkResult<List<LogDTO>, FederationError> {
        return execute(GetLogsBySeverityArgs(severity, limit), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: GetLogsBySeverityArgs,
        sessionContext: SessionContext,
        applyDuring: (GetLogsBySeverityArgs) -> GetLogsBySeverityArgs
    ): IdkResult<List<LogDTO>, FederationError> {
        val (severity, limit) = applyDuring(args)

        logger.debug("Retrieving logs by severity: '$severity', limit: $limit")

        return try {
            val logs = logQueries.getLogsBySeverity(severity, limit).executeAsList().map { it.toDTO() }
            logger.debug("Retrieved ${logs.size} logs with severity: '$severity'")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to retrieve logs by severity: '$severity'", e)
            IdkResult.err(ServerError("Failed to retrieve logs by severity", e.message, e))
        }
    }
}

package com.sphereon.openid.fed.services.command.log

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
) : TypedServiceCommandAdapter<GetLogsBySeverityArgs, List<LogDTO>>(
    commandId = GetLogsBySeverityCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetLogsBySeverityArgs>(),
    outputTypeToken = typeToken<List<LogDTO>>()
), GetLogsBySeverityCommand {

    private val logger = execution.federationLogger("GetLogsBySeverityCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun doExecute(
        args: GetLogsBySeverityArgs,
        applyDuring: (GetLogsBySeverityArgs) -> GetLogsBySeverityArgs
    ): IdkResult<List<LogDTO>, IdkError> {
        val (severity, limit) = applyDuring(args)

        logger.debug("Retrieving logs by severity: '$severity', limit: $limit")

        return try {
            val logs = logQueries.getLogsBySeverity(severity, limit).executeAsList().map { it.toDTO() }
            logger.debug("Retrieved ${logs.size} logs with severity: '$severity'")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to retrieve logs by severity: '$severity'", e)
            federationErr(ServerError("Failed to retrieve logs by severity", e.message, e))
        }
    }
}

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
 * Implementation of the GetLogsByTagCommand.
 * Retrieves log entries filtered by tag.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetLogsByTagCommand::class)
class GetLogsByTagCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetLogsByTagArgs, List<LogDTO>>(
    commandId = GetLogsByTagCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetLogsByTagArgs>(),
    outputTypeToken = typeToken<List<LogDTO>>()
), GetLogsByTagCommand {

    private val logger = execution.federationLogger("GetLogsByTagCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun doExecute(
        args: GetLogsByTagArgs,
        applyDuring: (GetLogsByTagArgs) -> GetLogsByTagArgs
    ): IdkResult<List<LogDTO>, IdkError> {
        val (tag, limit) = applyDuring(args)

        logger.debug("Retrieving logs by tag: '$tag', limit: $limit")

        return try {
            val logs = logQueries.getLogsByTag(tag, limit).executeAsList().map { it.toDTO() }
            logger.debug("Retrieved ${logs.size} logs with tag: '$tag'")
            IdkResult.ok(logs)
        } catch (e: Exception) {
            logger.error("Failed to retrieve logs by tag: '$tag'", e)
            federationErr(ServerError("Failed to retrieve logs by tag", e.message, e))
        }
    }
}

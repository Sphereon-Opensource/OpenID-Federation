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
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the InsertLogCommand.
 * Inserts a log entry into the log storage system.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = InsertLogCommand::class)
class InsertLogCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<InsertLogArgs, Unit>(
    commandId = InsertLogCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<InsertLogArgs>(),
    outputTypeToken = typeToken<Unit>()
), InsertLogCommand {

    private val logger = execution.federationLogger("InsertLogCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun doExecute(
        args: InsertLogArgs,
        applyDuring: (InsertLogArgs) -> InsertLogArgs
    ): IdkResult<Unit, IdkError> {
        val (level, message, tag, timestamp, throwable, metadata) = applyDuring(args)

        logger.debug("Inserting log entry with tag: $tag, level: $level")

        return try {
            logQueries.insertLog(
                severity = level.name,
                message = message,
                tag = tag,
                timestamp = timestamp,
                throwable_message = throwable?.message,
                throwable_stacktrace = throwable?.stackTraceToString(),
                metadata = if (metadata.isNotEmpty()) Json.encodeToString(metadata) else null
            )
            logger.debug("Successfully inserted log entry with tag: $tag")
            IdkResult.ok(Unit)
        } catch (e: Exception) {
            logger.error("Failed to insert log entry with tag: $tag", e)
            federationErr(ServerError("Failed to insert log", e.message, e))
        }
    }
}

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
) : ExecutionScopedCommandAdapter<InsertLogArgs, Unit, FederationError>(
    id = InsertLogCommand.COMMAND_ID,
    execution = execution
), InsertLogCommand {

    private val logger = Log.app().withTag("InsertLogCommand")
    private val logQueries = Persistence.logQueries

    override suspend fun insertLog(
        level: com.sphereon.core.api.log.LogLevel,
        message: String,
        tag: String,
        timestamp: Long,
        throwable: Throwable?,
        metadata: Map<String, String>
    ): IdkResult<Unit, FederationError> {
        return execute(InsertLogArgs(level, message, tag, timestamp, throwable, metadata), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: InsertLogArgs,
        sessionContext: SessionContext,
        applyDuring: (InsertLogArgs) -> InsertLogArgs
    ): IdkResult<Unit, FederationError> {
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
            IdkResult.err(ServerError("Failed to insert log", e.message, e))
        }
    }
}

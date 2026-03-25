package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the ListReceivedTrustMarksCommand.
 * Lists all received trust marks for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListReceivedTrustMarksCommand>())
class ListReceivedTrustMarksCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<ListReceivedTrustMarksArgs, Array<ReceivedTrustMark>>(
    commandId = ListReceivedTrustMarksCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ListReceivedTrustMarksArgs>(),
    outputTypeToken = typeToken<Array<ReceivedTrustMark>>()
), ListReceivedTrustMarksCommand {

    private val logger = execution.federationLogger("ListReceivedTrustMarksCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun doExecute(
        args: ListReceivedTrustMarksArgs,
        applyDuring: (ListReceivedTrustMarksArgs) -> ListReceivedTrustMarksArgs
    ): IdkResult<Array<ReceivedTrustMark>, IdkError> {
        val (tenantId) = applyDuring(args)
        val username = tenantId

        logger.debug("Listing trust marks for account: $username")

        return try {
            val trustMarks = receivedTrustMarkQueries.findByAccountId(tenantId).executeAsList()
            logger.debug("Found ${trustMarks.size} trust marks for account: $username")
            IdkResult.ok(trustMarks.map { it.toDTO() }.toTypedArray())
        } catch (e: Exception) {
            logger.error("Failed to list trust marks for account: $username", e)
            federationErr(ServerError("Failed to list received trust marks", e.message, e))
        }
    }
}

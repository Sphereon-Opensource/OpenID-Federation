package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the ListReceivedTrustMarksCommand.
 * Lists all received trust marks for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ListReceivedTrustMarksCommand::class)
class ListReceivedTrustMarksCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<ListReceivedTrustMarksArgs, Array<ReceivedTrustMark>, FederationError>(
    id = ListReceivedTrustMarksCommand.COMMAND_ID,
    execution = execution
), ListReceivedTrustMarksCommand {

    private val logger = Log.app().withTag("ListReceivedTrustMarksCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun listReceivedTrustMarks(account: Account): IdkResult<Array<ReceivedTrustMark>, FederationError> {
        return execute(ListReceivedTrustMarksArgs(account))
    }

    override suspend fun doExecute(
        args: ListReceivedTrustMarksArgs,
        applyDuring: (ListReceivedTrustMarksArgs) -> ListReceivedTrustMarksArgs
    ): IdkResult<Array<ReceivedTrustMark>, FederationError> {
        val (account) = applyDuring(args)
        val username = account.username

        logger.debug("Listing trust marks for account: $username")

        return try {
            val trustMarks = receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
            logger.debug("Found ${trustMarks.size} trust marks for account: $username")
            IdkResult.ok(trustMarks.map { it.toDTO() }.toTypedArray())
        } catch (e: Exception) {
            logger.error("Failed to list trust marks for account: $username", e)
            IdkResult.err(ServerError("Failed to list received trust marks", e.message, e))
        }
    }
}

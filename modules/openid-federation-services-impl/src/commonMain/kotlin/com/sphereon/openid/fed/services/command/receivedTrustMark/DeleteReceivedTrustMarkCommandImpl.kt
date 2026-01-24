package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ReceivedTrustMarkNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the DeleteReceivedTrustMarkCommand.
 * Deletes a received trust mark.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteReceivedTrustMarkCommand::class)
class DeleteReceivedTrustMarkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>(
    id = DeleteReceivedTrustMarkCommand.COMMAND_ID,
    execution = execution
), DeleteReceivedTrustMarkCommand {

    private val logger = Log.app().withTag("DeleteReceivedTrustMarkCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun deleteReceivedTrustMark(account: Account, trustMarkId: String): IdkResult<ReceivedTrustMark, FederationError> {
        return execute(DeleteReceivedTrustMarkArgs(account, trustMarkId), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: DeleteReceivedTrustMarkArgs,
        sessionContext: SessionContext,
        applyDuring: (DeleteReceivedTrustMarkArgs) -> DeleteReceivedTrustMarkArgs
    ): IdkResult<ReceivedTrustMark, FederationError> {
        val (account, trustMarkId) = applyDuring(args)
        val username = account.username

        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: $username")

        // Check if trust mark exists for this account
        val existing = receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
        if (existing == null) {
            logger.error("Trust mark not found with ID: $trustMarkId for account: $username")
            return IdkResult.err(ReceivedTrustMarkNotFoundError(trustMarkId, username))
        }

        return try {
            val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOneOrNull()

            if (deletedTrustMark != null) {
                logger.info("Successfully deleted trust mark ID: $trustMarkId for account: $username")
                IdkResult.ok(deletedTrustMark.toDTO())
            } else {
                logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username")
                IdkResult.err(ServerError("Failed to delete received trust mark"))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username", e)
            IdkResult.err(ServerError("Failed to delete received trust mark", e.message, e))
        }
    }
}

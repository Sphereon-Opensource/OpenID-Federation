package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ReceivedTrustMarkNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<DeleteReceivedTrustMarkArgs, ReceivedTrustMark>(
    commandId = DeleteReceivedTrustMarkCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteReceivedTrustMarkArgs>(),
    outputTypeToken = typeToken<ReceivedTrustMark>()
), DeleteReceivedTrustMarkCommand {

    private val logger = Log.app().withTag("DeleteReceivedTrustMarkCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun doExecute(
        args: DeleteReceivedTrustMarkArgs,
        applyDuring: (DeleteReceivedTrustMarkArgs) -> DeleteReceivedTrustMarkArgs
    ): IdkResult<ReceivedTrustMark, IdkError> {
        val (account, trustMarkId) = applyDuring(args)
        val username = account.username

        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: $username")

        // Check if trust mark exists for this account
        val existing = receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
        if (existing == null) {
            logger.error("Trust mark not found with ID: $trustMarkId for account: $username")
            return federationErr(ReceivedTrustMarkNotFoundError(trustMarkId, username))
        }

        return try {
            val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOneOrNull()

            if (deletedTrustMark != null) {
                logger.info("Successfully deleted trust mark ID: $trustMarkId for account: $username")
                IdkResult.ok(deletedTrustMark.toDTO())
            } else {
                logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username")
                federationErr(ServerError("Failed to delete received trust mark"))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark ID: $trustMarkId for account: $username", e)
            federationErr(ServerError("Failed to delete received trust mark", e.message, e))
        }
    }
}

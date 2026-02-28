package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.CriticalClaimNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Implementation of the DeleteCriticalClaimCommand.
 * Deletes a critical claim.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteCriticalClaimCommand::class)
class DeleteCriticalClaimCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteCriticalClaimArgs, CritEntity>(
    commandId = DeleteCriticalClaimCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteCriticalClaimArgs>(),
    outputTypeToken = typeToken<CritEntity>()
), DeleteCriticalClaimCommand {

    private val logger = Log.app().withTag("DeleteCriticalClaimCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun doExecute(
        args: DeleteCriticalClaimArgs,
        applyDuring: (DeleteCriticalClaimArgs) -> DeleteCriticalClaimArgs
    ): IdkResult<CritEntity, IdkError> {
        val (account, id) = applyDuring(args)

        logger.info("Deleting critical claim ID: $id for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        return try {
            val deletedCriticalClaim = critQueries
                .deleteByAccountIdAndId(account.id, id)
                .executeAsOneOrNull()

            if (deletedCriticalClaim != null) {
                logger.info("Successfully deleted critical claim with ID: $id")
                IdkResult.ok(deletedCriticalClaim)
            } else {
                logger.error("Critical claim not found with ID: $id for account: ${account.username}")
                federationErr(CriticalClaimNotFoundError(id))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete critical claim ID: $id for account: ${account.username}", e)
            federationErr(ServerError("Failed to delete critical claim", e.message, e))
        }
    }
}

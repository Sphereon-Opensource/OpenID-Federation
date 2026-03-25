package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.CriticalClaimNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Implementation of the DeleteCriticalClaimCommand.
 * Deletes a critical claim.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteCriticalClaimCommand>())
class DeleteCriticalClaimCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteCriticalClaimArgs, CritEntity>(
    commandId = DeleteCriticalClaimCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteCriticalClaimArgs>(),
    outputTypeToken = typeToken<CritEntity>()
), DeleteCriticalClaimCommand {

    private val logger = execution.federationLogger("DeleteCriticalClaimCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun doExecute(
        args: DeleteCriticalClaimArgs,
        applyDuring: (DeleteCriticalClaimArgs) -> DeleteCriticalClaimArgs
    ): IdkResult<CritEntity, IdkError> {
        val (tenantId, id) = applyDuring(args)

        logger.info("Deleting critical claim ID: $id for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        return try {
            val deletedCriticalClaim = critQueries
                .deleteByAccountIdAndId(tenantId, id)
                .executeAsOneOrNull()

            if (deletedCriticalClaim != null) {
                logger.info("Successfully deleted critical claim with ID: $id")
                IdkResult.ok(deletedCriticalClaim)
            } else {
                logger.error("Critical claim not found with ID: $id for account: ${tenantId}")
                federationErr(CriticalClaimNotFoundError(id))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete critical claim ID: $id for account: ${tenantId}", e)
            federationErr(ServerError("Failed to delete critical claim", e.message, e))
        }
    }
}

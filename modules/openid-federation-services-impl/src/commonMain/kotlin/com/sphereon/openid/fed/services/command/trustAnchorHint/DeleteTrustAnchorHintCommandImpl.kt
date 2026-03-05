package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.EntityNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TrustAnchorHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustAnchorHintCommand::class)
class DeleteTrustAnchorHintCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteTrustAnchorHintArgs, TrustAnchorHint>(
    commandId = DeleteTrustAnchorHintCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteTrustAnchorHintArgs>(),
    outputTypeToken = typeToken<TrustAnchorHint>()
), DeleteTrustAnchorHintCommand {

    private val logger = execution.federationLogger("DeleteTrustAnchorHintCommand")
    private val trustAnchorHintQueries = Persistence.trustAnchorHintQueries

    override suspend fun doExecute(
        args: DeleteTrustAnchorHintArgs,
        applyDuring: (DeleteTrustAnchorHintArgs) -> DeleteTrustAnchorHintArgs
    ): IdkResult<TrustAnchorHint, IdkError> {
        val (tenantId, trustAnchorHintId) = applyDuring(args)

        logger.debug("Attempting to delete trust anchor hint with id: $trustAnchorHintId for account: $tenantId")

        val trustAnchorHint = trustAnchorHintQueries
            .findByAccountIdAndId(tenantId, trustAnchorHintId)
            .executeAsOneOrNull()

        if (trustAnchorHint == null) {
            logger.error("Trust anchor hint not found with id: $trustAnchorHintId for account: $tenantId")
            return federationErr(EntityNotFoundError("trust_anchor_hint:$trustAnchorHintId"))
        }

        return try {
            val deleted = trustAnchorHintQueries.delete(trustAnchorHintId)
                .executeAsOneOrNull()
                ?.toDTO()

            if (deleted != null) {
                logger.info("Successfully deleted trust anchor hint with id: $trustAnchorHintId for account: $tenantId")
                IdkResult.ok(deleted)
            } else {
                logger.error("Failed to delete trust anchor hint with id: $trustAnchorHintId for account: $tenantId")
                federationErr(ServerError(Constants.FAILED_TO_DELETE_TRUST_ANCHOR_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete trust anchor hint with id: $trustAnchorHintId for account: $tenantId", e)
            federationErr(ServerError("Failed to delete trust anchor hint", e.message, e))
        }
    }
}

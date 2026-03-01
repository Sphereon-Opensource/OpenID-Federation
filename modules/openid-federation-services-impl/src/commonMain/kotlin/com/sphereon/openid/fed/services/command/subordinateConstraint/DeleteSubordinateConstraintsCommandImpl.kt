package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.EntityNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateConstraintsCommand::class)
class DeleteSubordinateConstraintsCommandImpl(
    execution: SessionExecution,
    private val json: Json
) : TypedServiceCommandAdapter<DeleteSubordinateConstraintsArgs, SubordinateConstraints>(
    commandId = DeleteSubordinateConstraintsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteSubordinateConstraintsArgs>(),
    outputTypeToken = typeToken<SubordinateConstraints>()
), DeleteSubordinateConstraintsCommand {

    private val logger = Log.app().withTag("DeleteSubordinateConstraintsCommand")
    private val subordinateConstraintQueries = Persistence.subordinateConstraintQueries

    override suspend fun doExecute(
        args: DeleteSubordinateConstraintsArgs,
        applyDuring: (DeleteSubordinateConstraintsArgs) -> DeleteSubordinateConstraintsArgs
    ): IdkResult<SubordinateConstraints, IdkError> {
        val (tenantId, subordinateId) = applyDuring(args)

        logger.debug("Deleting constraints for subordinate: $subordinateId, account: $tenantId")

        return try {
            val existing = subordinateConstraintQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinateId)
                .executeAsOneOrNull()

            if (existing == null) {
                return federationErr(EntityNotFoundError("subordinate_constraint:$subordinateId"))
            }

            val deleted = subordinateConstraintQueries
                .deleteBySubordinateId(subordinateId)
                .executeAsOneOrNull()

            if (deleted != null) {
                logger.info("Successfully deleted constraints for subordinate: $subordinateId")
                IdkResult.ok(deleted.toDTO(json))
            } else {
                logger.error("Failed to delete constraints for subordinate: $subordinateId")
                federationErr(ServerError("Failed to delete subordinate constraints"))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete constraints for subordinate: $subordinateId", e)
            federationErr(ServerError("Failed to delete subordinate constraints", e.message, e))
        }
    }
}

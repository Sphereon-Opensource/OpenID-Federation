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
@ContributesBinding(SessionScope::class, boundType = GetSubordinateConstraintsCommand::class)
class GetSubordinateConstraintsCommandImpl(
    execution: SessionExecution,
    private val json: Json
) : TypedServiceCommandAdapter<GetSubordinateConstraintsArgs, SubordinateConstraints>(
    commandId = GetSubordinateConstraintsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetSubordinateConstraintsArgs>(),
    outputTypeToken = typeToken<SubordinateConstraints>()
), GetSubordinateConstraintsCommand {

    private val logger = Log.app().withTag("GetSubordinateConstraintsCommand")
    private val subordinateConstraintQueries = Persistence.subordinateConstraintQueries

    override suspend fun doExecute(
        args: GetSubordinateConstraintsArgs,
        applyDuring: (GetSubordinateConstraintsArgs) -> GetSubordinateConstraintsArgs
    ): IdkResult<SubordinateConstraints, IdkError> {
        val (tenantId, subordinateId) = applyDuring(args)

        logger.debug("Getting constraints for subordinate: $subordinateId, account: $tenantId")

        return try {
            val constraint = subordinateConstraintQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinateId)
                .executeAsOneOrNull()

            if (constraint == null) {
                return federationErr(EntityNotFoundError("subordinate_constraint:$subordinateId"))
            }

            IdkResult.ok(constraint.toDTO(json))
        } catch (e: Exception) {
            logger.error("Failed to get constraints for subordinate: $subordinateId", e)
            federationErr(ServerError("Failed to get subordinate constraints", e.message, e))
        }
    }
}

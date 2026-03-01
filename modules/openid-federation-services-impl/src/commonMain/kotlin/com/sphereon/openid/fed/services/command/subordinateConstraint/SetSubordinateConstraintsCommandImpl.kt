package com.sphereon.openid.fed.services.command.subordinateConstraint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.SubordinateConstraints
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = SetSubordinateConstraintsCommand::class)
class SetSubordinateConstraintsCommandImpl(
    execution: SessionExecution,
    private val json: Json
) : TypedServiceCommandAdapter<SetSubordinateConstraintsArgs, SubordinateConstraints>(
    commandId = SetSubordinateConstraintsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SetSubordinateConstraintsArgs>(),
    outputTypeToken = typeToken<SubordinateConstraints>()
), SetSubordinateConstraintsCommand {

    private val logger = Log.app().withTag("SetSubordinateConstraintsCommand")
    private val subordinateConstraintQueries = Persistence.subordinateConstraintQueries

    override suspend fun doExecute(
        args: SetSubordinateConstraintsArgs,
        applyDuring: (SetSubordinateConstraintsArgs) -> SetSubordinateConstraintsArgs
    ): IdkResult<SubordinateConstraints, IdkError> {
        val (tenantId, subordinateId, constraints) = applyDuring(args)

        logger.debug("Setting constraints for subordinate: $subordinateId, account: $tenantId")

        val constraintsJson = json.encodeToString(constraints)

        return try {
            // Check if constraints already exist for this subordinate
            val existing = subordinateConstraintQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinateId)
                .executeAsOneOrNull()

            val result = if (existing != null) {
                // Update existing constraints
                subordinateConstraintQueries.update(constraintsJson, subordinateId)
                    .executeAsOneOrNull()
            } else {
                // Create new constraints
                subordinateConstraintQueries.create(tenantId, subordinateId, constraintsJson)
                    .executeAsOneOrNull()
            }

            if (result != null) {
                logger.info("Successfully set constraints for subordinate: $subordinateId")
                IdkResult.ok(result.toDTO(json))
            } else {
                logger.error("Failed to set constraints for subordinate: $subordinateId")
                federationErr(ServerError("Failed to set subordinate constraints"))
            }
        } catch (e: Exception) {
            logger.error("Failed to set constraints for subordinate: $subordinateId", e)
            federationErr(ServerError("Failed to set subordinate constraints", e.message, e))
        }
    }
}

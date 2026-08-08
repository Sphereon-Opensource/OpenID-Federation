package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.CriticalClaimAlreadyExistsError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Implementation of the CreateCriticalClaimCommand.
 * Creates a new critical claim for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateCriticalClaimCommand>())
class CreateCriticalClaimCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateCriticalClaimArgs, CritEntity, FederationError>(
    commandId = CreateCriticalClaimCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateCriticalClaimArgs>(),
    outputTypeToken = typeToken<CritEntity>()
), CreateCriticalClaimCommand {

    private val logger = execution.federationLogger("CreateCriticalClaimCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun doExecute(
        args: CreateCriticalClaimArgs,
        applyDuring: (CreateCriticalClaimArgs) -> CreateCriticalClaimArgs
    ): IdkResult<CritEntity, FederationError> {
        val (tenantId, claim) = applyDuring(args)

        logger.info("Creating critical claim for account: ${tenantId}, claim: $claim")
        logger.debug("Using account with ID: ${tenantId}")

        val existingCriticalClaim = critQueries
            .findByAccountIdAndClaim(tenantId, claim)
            .executeAsOneOrNull()

        if (existingCriticalClaim != null) {
            logger.warn("Critical claim already exists for claim: $claim")
            return federationErr(CriticalClaimAlreadyExistsError(tenantId, claim))
        }

        return try {
            val createdCriticalClaim = critQueries
                .create(tenantId, claim)
                .executeAsOneOrNull()

            if (createdCriticalClaim != null) {
                logger.info("Successfully created critical claim with ID: ${createdCriticalClaim.id}")
                IdkResult.ok(createdCriticalClaim)
            } else {
                logger.error("Failed to create critical claim for account: ${tenantId}, claim: $claim")
                federationErr(ServerError(Constants.FAILED_TO_CREATE_CRIT))
            }
        } catch (e: Exception) {
            logger.error("Failed to create critical claim for account: ${tenantId}, claim: $claim", e)
            federationErr(ServerError("Failed to create critical claim", e.message, e))
        }
    }
}

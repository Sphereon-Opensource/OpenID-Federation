package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Implementation of the FindCriticalClaimsByAccountCommand.
 * Finds all critical claims for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindCriticalClaimsByAccountCommand>())
class FindCriticalClaimsByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindCriticalClaimsByAccountArgs, Array<CritEntity>, FederationError>(
    commandId = FindCriticalClaimsByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindCriticalClaimsByAccountArgs>(),
    outputTypeToken = typeToken<Array<CritEntity>>()
), FindCriticalClaimsByAccountCommand {

    private val logger = execution.federationLogger("FindCriticalClaimsByAccountCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun doExecute(
        args: FindCriticalClaimsByAccountArgs,
        applyDuring: (FindCriticalClaimsByAccountArgs) -> FindCriticalClaimsByAccountArgs
    ): IdkResult<Array<CritEntity>, FederationError> {
        val (tenantId) = applyDuring(args)

        logger.info("Finding critical claims for account: ${tenantId}")
        logger.debug("Using account with ID: ${tenantId}")

        return try {
            val criticalClaims = critQueries.findByAccountId(tenantId).executeAsList().toTypedArray()
            logger.info("Found ${criticalClaims.size} critical claims for account: ${tenantId}")
            IdkResult.ok(criticalClaims)
        } catch (e: Exception) {
            logger.error("Failed to find critical claims for account: ${tenantId}", e)
            federationErr(ServerError("Failed to retrieve critical claims", e.message, e))
        }
    }
}

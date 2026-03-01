package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Implementation of the FindCriticalClaimsByAccountCommand.
 * Finds all critical claims for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindCriticalClaimsByAccountCommand::class)
class FindCriticalClaimsByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindCriticalClaimsByAccountArgs, Array<CritEntity>>(
    commandId = FindCriticalClaimsByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindCriticalClaimsByAccountArgs>(),
    outputTypeToken = typeToken<Array<CritEntity>>()
), FindCriticalClaimsByAccountCommand {

    private val logger = Log.app().withTag("FindCriticalClaimsByAccountCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun doExecute(
        args: FindCriticalClaimsByAccountArgs,
        applyDuring: (FindCriticalClaimsByAccountArgs) -> FindCriticalClaimsByAccountArgs
    ): IdkResult<Array<CritEntity>, IdkError> {
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

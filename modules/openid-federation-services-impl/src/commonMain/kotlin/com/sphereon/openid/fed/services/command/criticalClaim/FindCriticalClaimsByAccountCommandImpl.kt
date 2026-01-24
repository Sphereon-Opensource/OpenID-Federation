package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
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
) : ExecutionScopedCommandAdapter<FindCriticalClaimsByAccountArgs, Array<CritEntity>, FederationError>(
    id = FindCriticalClaimsByAccountCommand.COMMAND_ID,
    execution = execution
), FindCriticalClaimsByAccountCommand {

    private val logger = Log.app().withTag("FindCriticalClaimsByAccountCommand")
    private val critQueries = Persistence.critQueries

    override suspend fun findByAccount(account: Account): IdkResult<Array<CritEntity>, FederationError> {
        return execute(FindCriticalClaimsByAccountArgs(account), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: FindCriticalClaimsByAccountArgs,
        sessionContext: SessionContext,
        applyDuring: (FindCriticalClaimsByAccountArgs) -> FindCriticalClaimsByAccountArgs
    ): IdkResult<Array<CritEntity>, FederationError> {
        val (account) = applyDuring(args)

        logger.info("Finding critical claims for account: ${account.username}")
        logger.debug("Using account with ID: ${account.id}")

        return try {
            val criticalClaims = critQueries.findByAccountId(account.id).executeAsList().toTypedArray()
            logger.info("Found ${criticalClaims.size} critical claims for account: ${account.username}")
            IdkResult.ok(criticalClaims)
        } catch (e: Exception) {
            logger.error("Failed to find critical claims for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to retrieve critical claims", e.message, e))
        }
    }
}

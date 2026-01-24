package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Arguments for the FindCriticalClaimsByAccount command.
 */
data class FindCriticalClaimsByAccountArgs(
    val account: Account
)

/**
 * Service interface for find critical claims by account operation.
 */
interface FindCriticalClaimsByAccountCommandService {
    /**
     * Retrieves all critical claims associated with the provided account.
     *
     * @param account The account for which critical claims are to be retrieved.
     * @return IdkResult containing an array of CritEntity or an error.
     */
    suspend fun findByAccount(account: Account): IdkResult<Array<CritEntity>, FederationError>
}

/**
 * Command to find all critical claims for an account.
 */
interface FindCriticalClaimsByAccountCommand : Command<FindCriticalClaimsByAccountArgs, Array<CritEntity>, FederationError>, FindCriticalClaimsByAccountCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.critical-claim.find-by-account"
    }
}

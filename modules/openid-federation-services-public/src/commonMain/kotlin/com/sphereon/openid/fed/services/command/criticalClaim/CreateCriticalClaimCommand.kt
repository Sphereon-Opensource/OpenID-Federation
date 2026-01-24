package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Arguments for the CreateCriticalClaim command.
 */
data class CreateCriticalClaimArgs(
    val account: Account,
    val claim: String
)

/**
 * Service interface for create critical claim operation.
 */
interface CreateCriticalClaimCommandService {
    /**
     * Creates a critical claim for the given account and claim string.
     *
     * @param account The account for which the critical claim will be created.
     * @param claim The claim string that uniquely identifies the critical claim.
     * @return IdkResult containing the created CritEntity or an error.
     */
    suspend fun create(account: Account, claim: String): IdkResult<CritEntity, FederationError>
}

/**
 * Command to create a new critical claim for an account.
 */
interface CreateCriticalClaimCommand : Command<CreateCriticalClaimArgs, CritEntity, FederationError>, CreateCriticalClaimCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.critical-claim.create"
    }
}

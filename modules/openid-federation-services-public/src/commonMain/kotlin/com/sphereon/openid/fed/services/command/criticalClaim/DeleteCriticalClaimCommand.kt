package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.persistence.models.Crit as CritEntity

/**
 * Arguments for the DeleteCriticalClaim command.
 */
data class DeleteCriticalClaimArgs(
    val account: Account,
    val id: String
)

/**
 * Service interface for delete critical claim operation.
 */
interface DeleteCriticalClaimCommandService {
    /**
     * Deletes a critical claim associated with a given account and claim ID.
     *
     * @param account The account from which the critical claim will be deleted.
     * @param id The unique identifier of the critical claim to be deleted.
     * @return IdkResult containing the deleted CritEntity or an error.
     */
    suspend fun delete(account: Account, id: String): IdkResult<CritEntity, FederationError>
}

/**
 * Command to delete a critical claim.
 */
interface DeleteCriticalClaimCommand : Command<DeleteCriticalClaimArgs, CritEntity, FederationError>, DeleteCriticalClaimCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.critical-claim.delete"
    }
}

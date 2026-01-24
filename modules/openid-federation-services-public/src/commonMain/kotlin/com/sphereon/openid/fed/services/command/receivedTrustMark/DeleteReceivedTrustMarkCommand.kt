package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

/**
 * Arguments for the DeleteReceivedTrustMark command.
 */
data class DeleteReceivedTrustMarkArgs(
    val account: Account,
    val trustMarkId: String
)

/**
 * Service interface for delete received trust mark operation.
 */
interface DeleteReceivedTrustMarkCommandService {
    /**
     * Deletes a received trust mark associated with the specified account and trust mark ID.
     *
     * @param account The account from which the trust mark will be deleted.
     * @param trustMarkId The unique identifier of the trust mark to be deleted.
     * @return IdkResult containing the deleted ReceivedTrustMark or an error.
     */
    suspend fun deleteReceivedTrustMark(account: Account, trustMarkId: String): IdkResult<ReceivedTrustMark, FederationError>
}

/**
 * Command to delete a received trust mark.
 */
interface DeleteReceivedTrustMarkCommand : Command<DeleteReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>, DeleteReceivedTrustMarkCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.received-trust-mark.delete"
    }
}

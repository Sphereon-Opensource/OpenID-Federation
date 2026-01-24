package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

/**
 * Arguments for the ListReceivedTrustMarks command.
 */
data class ListReceivedTrustMarksArgs(
    val account: Account
)

/**
 * Service interface for list received trust marks operation.
 */
interface ListReceivedTrustMarksCommandService {
    /**
     * Retrieves a list of Trust Marks associated with the given account.
     *
     * @param account The account for which the received Trust Marks are to be retrieved.
     * @return IdkResult containing an array of ReceivedTrustMark or an error.
     */
    suspend fun listReceivedTrustMarks(account: Account): IdkResult<Array<ReceivedTrustMark>, FederationError>
}

/**
 * Command to list all received trust marks for an account.
 */
interface ListReceivedTrustMarksCommand : Command<ListReceivedTrustMarksArgs, Array<ReceivedTrustMark>, FederationError>, ListReceivedTrustMarksCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.received-trust-mark.list"
    }
}

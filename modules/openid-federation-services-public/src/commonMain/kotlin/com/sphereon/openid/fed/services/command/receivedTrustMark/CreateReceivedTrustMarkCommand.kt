package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

/**
 * Arguments for the CreateReceivedTrustMark command.
 */
data class CreateReceivedTrustMarkArgs(
    val account: Account,
    val createRequest: CreateReceivedTrustMark
)

/**
 * Service interface for create received trust mark operation.
 */
interface CreateReceivedTrustMarkCommandService {
    /**
     * Creates a new received trust mark for the given account based on the provided request.
     *
     * @param account The account for which the trust mark is to be created.
     * @param createRequest The request containing the details required to create the trust mark.
     * @return IdkResult containing the created ReceivedTrustMark or an error.
     */
    suspend fun createReceivedTrustMark(account: Account, createRequest: CreateReceivedTrustMark): IdkResult<ReceivedTrustMark, FederationError>
}

/**
 * Command to create a new received trust mark for an account.
 */
interface CreateReceivedTrustMarkCommand : Command<CreateReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>, CreateReceivedTrustMarkCommandService {
    companion object {
        const val COMMAND_ID = "fed.services.received-trust-mark.create"
    }
}

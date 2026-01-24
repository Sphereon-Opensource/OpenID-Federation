package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.CreateReceivedTrustMarkCommandService
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.DeleteReceivedTrustMarkCommandService
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksCommand
import com.sphereon.openid.fed.services.command.receivedTrustMark.ListReceivedTrustMarksCommandService

/**
 * Service interface responsible for handling operations related to received trust marks.
 * Provides functionalities to create, list, and delete trust marks associated with user accounts.
 *
 * This service aggregates all received trust mark-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface ReceivedTrustMarkService :
    CreateReceivedTrustMarkCommandService,
    DeleteReceivedTrustMarkCommandService,
    ListReceivedTrustMarksCommandService {

    /**
     * Provides access to individual received trust mark commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all received trust mark-related commands.
     */
    interface Commands {
        val createReceivedTrustMark: CreateReceivedTrustMarkCommand
        val deleteReceivedTrustMark: DeleteReceivedTrustMarkCommand
        val listReceivedTrustMarks: ListReceivedTrustMarksCommand
    }

    // Convenience methods that delegate to command services

    /**
     * Creates a new received trust mark for the given account based on the provided request.
     *
     * @param account The account for which the trust mark is to be created.
     * @param createRequest The request containing the details required to create the trust mark.
     * @return FederationResult containing the created ReceivedTrustMark or an error.
     */
    override suspend fun createReceivedTrustMark(account: Account, createRequest: CreateReceivedTrustMark): FederationResult<ReceivedTrustMark>

    /**
     * Retrieves a list of Trust Marks associated with the given account.
     *
     * @param account The account for which the received Trust Marks are to be retrieved.
     * @return FederationResult containing an array of ReceivedTrustMark or an error.
     */
    override suspend fun listReceivedTrustMarks(account: Account): FederationResult<Array<ReceivedTrustMark>>

    /**
     * Deletes a received trust mark associated with the specified account and trust mark ID.
     *
     * @param account The account from which the trust mark will be deleted.
     * @param trustMarkId The unique identifier of the trust mark to be deleted.
     * @return FederationResult containing the deleted ReceivedTrustMark or an error.
     */
    override suspend fun deleteReceivedTrustMark(account: Account, trustMarkId: String): FederationResult<ReceivedTrustMark>
}

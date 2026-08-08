package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark

/**
 * Service interface responsible for handling operations related to received trust marks.
 * Provides functionalities to create, list, and delete trust marks associated with user accounts.
 *
 * This service aggregates all received trust mark-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface ReceivedTrustMarkService {

    // Convenience methods that delegate to command services

    /**
     * Creates a new received trust mark for the given account based on the provided request.
     *
     * @param account The account for which the trust mark is to be created.
     * @param createRequest The request containing the details required to create the trust mark.
     * @return FederationResult containing the created ReceivedTrustMark or an error.
     */
    suspend fun createReceivedTrustMark(tenantId: String, createRequest: CreateReceivedTrustMark): FederationResult<ReceivedTrustMark>

    /**
     * Retrieves a list of Trust Marks associated with the given account.
     *
     * @param account The account for which the received Trust Marks are to be retrieved.
     * @return FederationResult containing an array of ReceivedTrustMark or an error.
     */
    suspend fun listReceivedTrustMarks(tenantId: String): FederationResult<Array<ReceivedTrustMark>>

    /**
     * Deletes a received trust mark associated with the specified account and trust mark ID.
     *
     * @param account The account from which the trust mark will be deleted.
     * @param trustMarkId The unique identifier of the trust mark to be deleted.
     * @return FederationResult containing the deleted ReceivedTrustMark or an error.
     */
    suspend fun deleteReceivedTrustMark(tenantId: String, trustMarkId: String): FederationResult<ReceivedTrustMark>
}

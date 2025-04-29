package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.admin.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO

/**
 * Service responsible for handling operations related to received trust marks.
 * Provides functionalities to create, list, and delete trust marks associated with user accounts.
 */
class ReceivedTrustMarkService {
    /**
     * Logger instance used for logging messages specific to the `ReceivedTrustMarkService` class.
     * It is initialized with a unique tag to identify logs related to this service.
     */
    private val logger = Logger.tag("ReceivedTrustMarkService")

    /**
     * Provides access to persistence operations related to received trust marks.
     * Used to perform database queries for managing trust marks within the service.
     */
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    /**
     * Creates a new received trust mark for the given account based on the provided request.
     *
     * @param account The account for which the trust mark is to be created.
     * @param createRequest The request containing the details required to create the trust mark,
     *                      including the trust mark type identifier and the JWT.
     * @return The newly created received trust mark as a data object.
     */
    fun createReceivedTrustMark(
        account: Account,
        createRequest: CreateReceivedTrustMark
    ): ReceivedTrustMark {
        val username = account.username
        logger.info("Creating trust mark for account: $username")
        val createdTrustMark = receivedTrustMarkQueries.create(
            account_id = account.id,
            trust_mark_id = createRequest.trustMarkId,
            jwt = createRequest.jwt,
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${createdTrustMark.id}")
        return createdTrustMark.toDTO()
    }

    /**
     * Retrieves a list of Trust Marks associated with the given account.
     *
     * @param account The account for which the received Trust Marks are to be retrieved.
     * @return An array of ReceivedTrustMark objects associated with the provided account.
     */
    fun listReceivedTrustMarks(account: Account): Array<ReceivedTrustMark> {
        val username = account.username
        logger.debug("Listing trust marks for account: $username")
        val trustMarks = receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
        logger.debug("Found ${trustMarks.size} trust marks for account: $username")
        return trustMarks.map { it.toDTO() }.toTypedArray()
    }

    /**
     * Deletes a received trust mark associated with the specified account and trust mark ID.
     *
     * @param account The account from which the trust mark will be deleted.
     * @param trustMarkId The unique identifier of the trust mark to be deleted.
     * @return The deleted trust mark as an object of ReceivedTrustMark.
     * @throws NotFoundException If the trust mark with the specified ID does not exist for the account.
     */
    fun deleteReceivedTrustMark(
        account: Account,
        trustMarkId: String
    ): ReceivedTrustMark {
        val username = account.username
        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: $username")
        ensureTrustMarkExists(account, trustMarkId)
        val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOne()
        logger.info("Successfully deleted trust mark ID: $trustMarkId for account: $username")
        return deletedTrustMark.toDTO()
    }

    /**
     * Ensures that a trust mark with the given ID exists for the specified account. If the trust mark
     * is not found, logs an error and throws a `NotFoundException`.
     *
     * @param account The account for which the trust mark needs to be validated.
     * @param trustMarkId The unique identifier of the trust mark to verify.
     */
    private fun ensureTrustMarkExists(account: Account, trustMarkId: String) {
        val username = account.username
        val existing = receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
        if (existing == null) {
            logger.error("Trust mark not found with ID: $trustMarkId for account: $username")
            throw NotFoundException("Received TrustMark with ID '$trustMarkId' not found for account '$username'.")
        }
    }
}

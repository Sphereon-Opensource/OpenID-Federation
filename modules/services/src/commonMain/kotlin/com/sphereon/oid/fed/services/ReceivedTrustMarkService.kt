package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toReceivedTrustMarkDTO

class ReceivedTrustMarkService {
    private val logger = Logger.tag("ReceivedTrustMarkService")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    fun create(
        username: String,
        dto: CreateReceivedTrustMarkDTO,
        accountService: AccountService
    ): ReceivedTrustMarkDTO {
        logger.info("Creating trust mark for username: $username")
        val account = accountService.getAccountByUsername(username)
        logger.debug("Found account with ID: ${account.id}")

        val receivedTrustMark = receivedTrustMarkQueries.create(
            account_id = account.id,
            trust_mark_type_identifier = dto.trustMarkTypeIdentifier,
            jwt = dto.jwt,
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${receivedTrustMark.id}")

        return receivedTrustMark.toReceivedTrustMarkDTO()
    }

    fun list(username: String, accountService: AccountService): List<ReceivedTrustMarkDTO> {
        logger.debug("Listing trust marks for username: $username")
        val account = accountService.getAccountByUsername(username)

        val trustMarks = receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
            .map { it.toReceivedTrustMarkDTO() }
        logger.debug("Found ${trustMarks.size} trust marks for username: $username")

        return trustMarks
    }

    fun delete(username: String, trustMarkId: Int, accountService: AccountService): ReceivedTrustMarkDTO {
        logger.info("Attempting to delete trust mark ID: $trustMarkId for username: $username")
        val account = accountService.getAccountByUsername(username)

        receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
            ?: throw NotFoundException("Received TrustMark with ID '$trustMarkId' not found for account '$username'.").also {
                logger.error("Trust mark not found with ID: $trustMarkId for username: $username")
            }

        val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOne().toReceivedTrustMarkDTO()
        logger.info("Successfully deleted trust mark ID: $trustMarkId for username: $username")

        return deletedTrustMark
    }
}

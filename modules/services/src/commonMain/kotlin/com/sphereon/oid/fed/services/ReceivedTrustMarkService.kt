package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.mappers.toReceivedTrustMarkDTO

class ReceivedTrustMarkService {
    private val logger = Logger.tag("ReceivedTrustMarkService")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    fun create(
        account: Account,
        dto: CreateReceivedTrustMarkDTO
    ): ReceivedTrustMarkDTO {
        logger.info("Creating trust mark for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        val receivedTrustMark = receivedTrustMarkQueries.create(
            account_id = account.id,
            trust_mark_type_identifier = dto.trustMarkTypeIdentifier,
            jwt = dto.jwt,
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${receivedTrustMark.id}")

        return receivedTrustMark.toReceivedTrustMarkDTO()
    }

    fun create(
        username: String,
        dto: CreateReceivedTrustMarkDTO,
        accountService: AccountService
    ): ReceivedTrustMarkDTO {
        val account = accountService.getAccountByUsername(username)
        return create(account, dto)
    }

    fun list(account: Account): List<ReceivedTrustMarkDTO> {
        logger.debug("Listing trust marks for account: ${account.username}")

        val trustMarks = receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
            .map { it.toReceivedTrustMarkDTO() }
        logger.debug("Found ${trustMarks.size} trust marks for account: ${account.username}")

        return trustMarks
    }

    fun list(
        username: String,
        accountService: AccountService
    ): List<ReceivedTrustMarkDTO> {
        val account = accountService.getAccountByUsername(username)
        return list(account)
    }

    fun delete(
        account: Account,
        trustMarkId: Int
    ): ReceivedTrustMarkDTO {
        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: ${account.username}")

        receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
            ?: throw NotFoundException("Received TrustMark with ID '$trustMarkId' not found for account '${account.username}'.").also {
                logger.error("Trust mark not found with ID: $trustMarkId for account: ${account.username}")
            }

        val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOne().toReceivedTrustMarkDTO()
        logger.info("Successfully deleted trust mark ID: $trustMarkId for account: ${account.username}")

        return deletedTrustMark
    }

    fun delete(
        username: String,
        trustMarkId: Int,
        accountService: AccountService
    ): ReceivedTrustMarkDTO {
        val account = accountService.getAccountByUsername(username)
        return delete(account, trustMarkId)
    }
}

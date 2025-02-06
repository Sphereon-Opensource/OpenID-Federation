package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO

class ReceivedTrustMarkService {
    private val logger = Logger.tag("ReceivedTrustMarkService")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    fun createReceivedTrustMark(
        account: Account,
        dto: CreateReceivedTrustMark
    ): ReceivedTrustMark {
        logger.info("Creating trust mark for account: ${account.username}")

        val receivedTrustMark = receivedTrustMarkQueries.create(
            account_id = account.id,
            trust_mark_type_identifier = dto.trustMarkTypeIdentifier,
            jwt = dto.jwt,
        ).executeAsOne()

        logger.info("Successfully created trust mark with ID: ${receivedTrustMark.id}")

        return receivedTrustMark.toDTO()
    }

    fun listReceivedTrustMarks(account: Account): Array<ReceivedTrustMark> {
        logger.debug("Listing trust marks for account: ${account.username}")

        val trustMarks = receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
        logger.debug("Found ${trustMarks.size} trust marks for account: ${account.username}")

        return trustMarks.map { it.toDTO() }.toTypedArray()
    }

    fun deleteReceivedTrustMark(
        account: Account,
        trustMarkId: Int
    ): ReceivedTrustMark {
        logger.info("Attempting to delete trust mark ID: $trustMarkId for account: ${account.username}")

        receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
            ?: throw NotFoundException("Received TrustMark with ID '$trustMarkId' not found for account '${account.username}'.").also {
                logger.error("Trust mark not found with ID: $trustMarkId for account: ${account.username}")
            }

        val deletedTrustMark = receivedTrustMarkQueries.delete(trustMarkId).executeAsOne()
        logger.info("Successfully deleted trust mark ID: $trustMarkId for account: ${account.username}")

        return deletedTrustMark.toDTO()
    }
}

package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toReceivedTrustMarkDTO

class ReceivedTrustMarkService {
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    fun create(
        username: String,
        dto: CreateReceivedTrustMarkDTO,
        accountService: AccountService
    ): ReceivedTrustMarkDTO {
        val account = accountService.getAccountByUsername(username)

        val receivedTrustMark = receivedTrustMarkQueries.create(
            account_id = account.id,
            trust_mark_type_id = dto.trustMarkTypeId,
            jwt = dto.jwt,
        ).executeAsOne()

        return receivedTrustMark.toReceivedTrustMarkDTO()
    }

    fun list(username: String, accountService: AccountService): List<ReceivedTrustMarkDTO> {
        val account = accountService.getAccountByUsername(username)
        return receivedTrustMarkQueries.findByAccountId(account.id).executeAsList()
            .map { it.toReceivedTrustMarkDTO() }
    }

    fun delete(username: String, trustMarkId: Int, accountService: AccountService): ReceivedTrustMarkDTO {
        val account = accountService.getAccountByUsername(username)

        receivedTrustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
            ?: throw NotFoundException("Received TrustMark with ID '$trustMarkId' not found for account '$username'.")

        return receivedTrustMarkQueries.delete(trustMarkId).executeAsOne().toReceivedTrustMarkDTO()
    }
}

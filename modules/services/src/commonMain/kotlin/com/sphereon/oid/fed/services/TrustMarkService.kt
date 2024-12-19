package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDefinitionDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkDefinitionDTO
import com.sphereon.oid.fed.openapi.models.UpdateTrustMarkDefinitionDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toTrustMarkDefinitionDTO

class TrustMarkService {
    private val trustMarkQueries = Persistence.trustMarkDefinitionQueries

    fun createTrustMarkDefinition(accountId: Int, createDto: CreateTrustMarkDefinitionDTO): TrustMarkDefinitionDTO {
        val existingDefinition =
            trustMarkQueries.findByAccountIdAndIdentifier(accountId, createDto.identifier).executeAsOneOrNull()
        if (existingDefinition != null) {
            throw EntityAlreadyExistsException("A trust mark definition with the given identifier already exists for this account.")
        }

        val createdDefinition = trustMarkQueries.create(
            account_id = accountId,
            identifier = createDto.identifier,
            name = createDto.name,
            description = createDto.description
        ).executeAsOne()

        return createdDefinition.toTrustMarkDefinitionDTO()
    }

    fun findAllByAccount(accountId: Int): List<TrustMarkDefinitionDTO> {
        return trustMarkQueries.findByAccountId(accountId).executeAsList().map { it.toTrustMarkDefinitionDTO() }
    }

    fun findById(accountId: Int, id: Int): TrustMarkDefinitionDTO {
        val definition = trustMarkQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")
        return definition.toTrustMarkDefinitionDTO()
    }

    fun findByIdentifier(accountId: Int, identifier: String): TrustMarkDefinitionDTO {
        val definition = trustMarkQueries.findByAccountIdAndIdentifier(accountId, identifier).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with identifier $identifier not found for account $accountId.")
        return definition.toTrustMarkDefinitionDTO()
    }

    fun updateTrustMarkDefinition(
        accountId: Int,
        id: Int,
        updateDto: UpdateTrustMarkDefinitionDTO
    ): TrustMarkDefinitionDTO {
        val existingDefinition = trustMarkQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")

        val updatedDefinition = trustMarkQueries.update(
            name = updateDto.name ?: existingDefinition.name,
            description = updateDto.description ?: existingDefinition.description,
            id = id
        ).executeAsOne()

        return updatedDefinition.toTrustMarkDefinitionDTO()
    }

    fun deleteTrustMarkDefinition(accountId: Int, id: Int): TrustMarkDefinitionDTO {
        val definition = trustMarkQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")

        val deletedDefinition = trustMarkQueries.delete(id).executeAsOne()
        return deletedDefinition.toTrustMarkDefinitionDTO()
    }
}

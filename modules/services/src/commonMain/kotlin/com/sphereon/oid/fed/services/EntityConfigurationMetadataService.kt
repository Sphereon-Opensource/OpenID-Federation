package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toEntityConfigurationMetadataDTO
import kotlinx.serialization.json.JsonObject

class EntityConfigurationMetadataService {
    fun createEntityConfigurationMetadata(
        accountUsername: String,
        key: String,
        metadata: JsonObject
    ): EntityConfigurationMetadataDTO {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        val metadataAlreadyExists =
            Persistence.entityConfigurationMetadataQueries.findByAccountIdAndKey(account.id, key).executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            throw EntityAlreadyExistsException(Constants.ENTITY_CONFIGURATION_METADATA_ALREADY_EXISTS)
        }

        val createdMetadata =
            Persistence.entityConfigurationMetadataQueries.create(account.id, key, metadata.toString())
                .executeAsOneOrNull()
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA)

        return createdMetadata.toEntityConfigurationMetadataDTO()
    }

    fun findByAccountUsername(accountUsername: String): Array<EntityConfigurationMetadataDTO> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
        return Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList()
            .map { it.toEntityConfigurationMetadataDTO() }.toTypedArray()
    }

    fun deleteEntityConfigurationMetadata(accountUsername: String, id: Int): EntityConfigurationMetadataDTO {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        val metadata =
            Persistence.entityConfigurationMetadataQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)

        if (metadata.account_id != account.id) {
            throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
        }

        val deletedMetadata = Persistence.entityConfigurationMetadataQueries.delete(id).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)

        return deletedMetadata.toEntityConfigurationMetadataDTO()
    }
}

package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadata
import kotlinx.serialization.json.JsonObject

class EntityConfigurationMetadataService {
    fun createEntityConfigurationMetadata(
        accountUsername: String,
        key: String,
        metadata: JsonObject
    ): EntityConfigurationMetadata {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val metadataAlreadyExists =
            Persistence.entityConfigurationMetadataQueries.findByAccountIdAndKey(account.id, key).executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            throw IllegalStateException(Constants.ENTITY_CONFIGURATION_METADATA_ALREADY_EXISTS)
        }

        return Persistence.entityConfigurationMetadataQueries.create(account.id, key, metadata.toString())
            .executeAsOneOrNull()
            ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA)
    }

    fun findByAccountId(accountId: Int): Array<EntityConfigurationMetadata> {
        return Persistence.entityConfigurationMetadataQueries.findByAccountId(accountId).executeAsList().toTypedArray()
    }

    fun findByAccountUsername(accountUsername: String): Array<EntityConfigurationMetadata> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)
        return Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun deleteEntityConfigurationMetadata(accountUsername: String, id: Int): EntityConfigurationMetadata {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val metadata =
            Persistence.entityConfigurationMetadataQueries.findById(id).executeAsOneOrNull()
                ?: throw IllegalArgumentException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)

        if (metadata.account_id != account.id) {
            throw IllegalArgumentException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
        }

        return Persistence.entityConfigurationMetadataQueries.delete(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
    }
}

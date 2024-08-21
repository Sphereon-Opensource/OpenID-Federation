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
            ?: throw IllegalArgumentException("Account not found")

        val metadataAlreadyExists =
            Persistence.entityConfigurationMetadataQueries.findByAccountIdAndKey(account.id, key).executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            throw IllegalStateException("Entity configuration metadata already exists")
        }

        return Persistence.entityConfigurationMetadataQueries.create(account.id, key, metadata.toString())
            .executeAsOneOrNull()
            ?: throw IllegalStateException("Failed to create entity configuration metadata")
    }

    fun findByAccountId(accountId: Int): Array<EntityConfigurationMetadata> {
        return Persistence.entityConfigurationMetadataQueries.findByAccountId(accountId).executeAsList().toTypedArray()
    }

    fun findByAccountUsername(accountUsername: String): Array<EntityConfigurationMetadata> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException("Account not found")
        return Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun deleteEntityConfigurationMetadata(accountUsername: String, id: Int): EntityConfigurationMetadata {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException("Account not found")

        val metadata =
            Persistence.entityConfigurationMetadataQueries.findById(id).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Entity configuration metadata not found")

        if (metadata.account_id != account.id) {
            throw IllegalArgumentException("Entity configuration metadata not found")
        }

        return Persistence.entityConfigurationMetadataQueries.delete(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException("Entity configuration metadata not found")
    }
}

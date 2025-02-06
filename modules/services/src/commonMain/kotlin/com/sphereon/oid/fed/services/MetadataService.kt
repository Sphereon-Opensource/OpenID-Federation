package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import kotlinx.serialization.json.JsonObject
import com.sphereon.oid.fed.persistence.models.Metadata as MetadataEntity

class MetadataService {
    private val logger = Logger.tag("EntityConfigurationMetadataService")

    fun createEntityConfigurationMetadata(
        account: Account,
        key: String,
        metadata: JsonObject
    ): MetadataEntity {
        logger.info("Creating entity configuration metadata for account: ${account.username}, key: $key")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val metadataAlreadyExists =
                Persistence.metadataQueries.findByAccountIdAndKey(account.id, key)
                    .executeAsOneOrNull()

            if (metadataAlreadyExists != null) {
                logger.error("Metadata already exists for account ID: ${account.id}, key: $key")
                throw EntityAlreadyExistsException(Constants.ENTITY_CONFIGURATION_METADATA_ALREADY_EXISTS)
            }

            val createdMetadata =
                Persistence.metadataQueries.create(account.id, key, metadata.toString())
                    .executeAsOneOrNull()
                    ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA).also {
                        logger.error("Failed to create metadata for account ID: ${account.id}, key: $key")
                    }

            logger.info("Successfully created metadata with ID: ${createdMetadata.id}")
            return createdMetadata
        } catch (e: Exception) {
            logger.error("Failed to create metadata for account: ${account.username}, key: $key", e)
            throw e
        }
    }

    fun findByAccount(account: Account): Array<MetadataEntity> {
        logger.debug("Finding metadata for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val metadata = Persistence.metadataQueries.findByAccountId(account.id).executeAsList().toTypedArray()
            logger.debug("Found ${metadata.size} metadata entries for account: ${account.username}")
            return metadata
        } catch (e: Exception) {
            logger.error("Failed to find metadata for account: ${account.username}", e)
            throw e
        }
    }

    fun deleteEntityConfigurationMetadata(account: Account, id: Int): MetadataEntity {
        logger.info("Deleting metadata ID: $id for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val metadata =
                Persistence.metadataQueries.findById(id).executeAsOneOrNull()
                    ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND).also {
                        logger.error("Metadata not found with ID: $id")
                    }

            if (metadata.account_id != account.id) {
                logger.error("Metadata ID: $id does not belong to account: ${account.username}")
                throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
            }

            val deletedMetadata = Persistence.metadataQueries.delete(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND).also {
                    logger.error("Failed to delete metadata ID: $id")
                }

            logger.info("Successfully deleted metadata ID: $id")
            return deletedMetadata
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id for account: ${account.username}", e)
            throw e
        }
    }
}

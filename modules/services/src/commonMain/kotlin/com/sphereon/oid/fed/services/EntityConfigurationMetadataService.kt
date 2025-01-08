package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.extensions.toEntityConfigurationMetadataDTO
import kotlinx.serialization.json.JsonObject

class EntityConfigurationMetadataService {
    private val logger = Logger.tag("EntityConfigurationMetadataService")

    fun createEntityConfigurationMetadata(
        accountUsername: String,
        key: String,
        metadata: JsonObject
    ): EntityConfigurationMetadataDTO {
        logger.info("Creating entity configuration metadata for account: $accountUsername, key: $key")
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername")
            }

        val metadataAlreadyExists =
            Persistence.entityConfigurationMetadataQueries.findByAccountIdAndKey(account.id, key).executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            logger.error("Metadata already exists for account ID: ${account.id}, key: $key")
            throw EntityAlreadyExistsException(Constants.ENTITY_CONFIGURATION_METADATA_ALREADY_EXISTS)
        }

        val createdMetadata =
            Persistence.entityConfigurationMetadataQueries.create(account.id, key, metadata.toString())
                .executeAsOneOrNull()
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA).also {
                    logger.error("Failed to create metadata for account ID: ${account.id}, key: $key")
                }

        logger.info("Successfully created metadata with ID: ${createdMetadata.id}")
        return createdMetadata.toEntityConfigurationMetadataDTO()
    }

    fun findByAccountUsername(accountUsername: String): Array<EntityConfigurationMetadataDTO> {
        logger.debug("Finding metadata for account: $accountUsername")
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername")
            }

        val metadata = Persistence.entityConfigurationMetadataQueries.findByAccountId(account.id).executeAsList()
            .map { it.toEntityConfigurationMetadataDTO() }.toTypedArray()
        logger.debug("Found ${metadata.size} metadata entries for account: $accountUsername")
        return metadata
    }

    fun deleteEntityConfigurationMetadata(accountUsername: String, id: Int): EntityConfigurationMetadataDTO {
        logger.info("Deleting metadata ID: $id for account: $accountUsername")
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found: $accountUsername")
            }

        val metadata =
            Persistence.entityConfigurationMetadataQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND).also {
                    logger.error("Metadata not found with ID: $id")
                }

        if (metadata.account_id != account.id) {
            logger.error("Metadata ID: $id does not belong to account: $accountUsername")
            throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
        }

        val deletedMetadata = Persistence.entityConfigurationMetadataQueries.delete(id).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND).also {
                logger.error("Failed to delete metadata ID: $id")
            }

        logger.info("Successfully deleted metadata ID: $id")
        return deletedMetadata.toEntityConfigurationMetadataDTO()
    }
}

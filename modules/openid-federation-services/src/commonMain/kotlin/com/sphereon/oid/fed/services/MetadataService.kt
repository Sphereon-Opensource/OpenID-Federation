package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.Metadata
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO
import kotlinx.serialization.json.JsonObject

/**
 * The MetadataService class provides operations for managing metadata configurations associated
 * with an account. The operations include creating, retrieving, and deleting metadata entries
 * while ensuring certain constraints are adhered to, such as one metadata entry per account-key pair.
 */
class MetadataService {
    /**
     * Logger instance specifically tagged for the MetadataService class.
     * Used to log messages and events related to metadata operations,
     * facilitating easier debugging and tracing within this service.
     */
    private val logger = Logger.tag("MetadataService")

    /**
     * Creates a new entity configuration metadata entry for a specified account and key.
     * If metadata for the given account ID and key already exists, an exception is thrown.
     * Logs the process and handles exceptions during the creation process.
     *
     * @param account The account for which the metadata is being created.
     * @param key The unique key representing the metadata.
     * @param metadata The metadata content to be associated with the account and key.
     * @return The created Metadata object containing details of the newly created entity.
     * @throws EntityAlreadyExistsException If metadata for the given account ID and key already exists.
     * @throws IllegalStateException If the metadata creation fails unexpectedly.
     * @throws Exception If any other error occurs during the creation process.
     */
    fun createMetadata(
        account: Account,
        key: String,
        metadata: JsonObject
    ): Metadata {
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
            return createdMetadata.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to create metadata for account: ${account.username}, key: $key", e)
            throw e
        }
    }

    /**
     * Finds and retrieves a list of Metadata associated with the provided account.
     *
     * @param account The account for which metadata is to be fetched. Must be a valid Account object.
     * @return A list of Metadata objects associated with the given account.
     * @throws Exception if there is an error during the retrieval process.
     */
    fun findByAccount(account: Account): List<Metadata> {
        logger.debug("Finding metadata for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val metadataList = Persistence.metadataQueries.findByAccountId(account.id).executeAsList()
            logger.debug("Found ${metadataList.size} metadata entries for account: ${account.username}")
            return metadataList.map { it.toDTO() }
        } catch (e: Exception) {
            logger.error("Failed to find metadata for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Deletes a metadata record associated with the given account and ID.
     *
     * @param account The account associated with the metadata to be deleted.
     * @param id The unique identifier of the metadata record to delete.
     * @return The deleted metadata record as a Metadata object.
     * @throws NotFoundException If the metadata record is not found or does not belong to the given account.
     * @throws Exception If an unexpected error occurs during the operation.
     */
    fun deleteMetadata(account: Account, id: String): Metadata {
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
            return deletedMetadata.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id for account: ${account.username}", e)
            throw e
        }
    }
}

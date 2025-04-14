package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.admin.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.admin.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.MetadataPolicy
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO
import kotlinx.serialization.json.JsonElement

/**
 * The MetadataPolicyService class provides operations for managing metadata policy configurations
 * associated with an account. The operations include creating, retrieving, and deleting metadata
 * policy entries while ensuring certain constraints are adhered to, such as one metadata policy
 * entry per account-key pair.
 */
class MetadataPolicyService {
    /**
     * Logger instance specifically tagged for the MetadataPolicyService class. Used to log messages
     * and events related to metadata policy operations, facilitating easier debugging and tracing
     * within this service.
     */
    private val logger = Logger.tag("MetadataPolicyService")

    /**
     * Creates a new entity configuration metadata policy entry for a specified account and key. If
     * metadata policy for the given account ID and key already exists, an exception is thrown. Logs
     * the process and handles exceptions during the creation process.
     *
     * @param account The account for which the metadata policy is being created.
     * @param key The unique key representing the metadata policy.
     * @param policy The policy content to be associated with the account and key.
     * @return The created MetadataPolicy object containing details of the newly created entity.
     * @throws EntityAlreadyExistsException If metadata policy for the given account ID and key
     * already exists.
     * @throws IllegalStateException If the metadata policy creation fails unexpectedly.
     * @throws Exception If any other error occurs during the creation process.
     */
    fun createPolicy(
        account: Account,
        key: String,
        policy: JsonElement
    ): MetadataPolicy {
        logger.info(
            "Creating entity configuration metadata policy for account: ${account.username}, key: $key"
        )
        try {
            logger.debug("Using account with ID: ${account.id}")

            val policyAlreadyExists =
                Persistence.metadataPolicyQueries
                    .findByAccountIdAndKey(account.id, key)
                    .executeAsOneOrNull()

            if (policyAlreadyExists != null) {
                logger.error(
                    "Metadata policy already exists for account ID: ${account.id}, key: $key"
                )
                throw EntityAlreadyExistsException(
                    Constants.ENTITY_CONFIGURATION_METADATA_POLICY_ALREADY_EXISTS
                )
            }

            val createdPolicy =
                Persistence.metadataPolicyQueries
                    .create(account.id, key, policy.toString())
                    .executeAsOneOrNull()
                    ?: throw IllegalStateException(
                        Constants
                            .FAILED_TO_CREATE_ENTITY_CONFIGURATION_METADATA_POLICY
                    )
                        .also {
                            logger.error(
                                "Failed to create metadata policy for account ID: ${account.id}, key: $key"
                            )
                        }

            logger.info("Successfully created metadata policy with ID: ${createdPolicy.id}")
            return createdPolicy.toDTO()
        } catch (e: Exception) {
            logger.error(
                "Failed to create metadata policy for account: ${account.username}, key: $key",
                e
            )
            throw e
        }
    }

    /**
     * Finds and retrieves a list of MetadataPolicy associated with the provided account.
     *
     * @param account The account for which metadata policy is to be fetched. Must be a valid
     * Account object.
     * @return A list of MetadataPolicy objects associated with the given account.
     * @throws Exception if there is an error during the retrieval process.
     */
    fun findByAccount(account: Account): List<MetadataPolicy> {
        logger.debug("Finding metadata policy for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val policyList =
                Persistence.metadataPolicyQueries.findByAccountId(account.id).executeAsList()
            logger.debug(
                "Found ${policyList.size} metadata policy entries for account: ${account.username}"
            )
            return policyList.map { it.toDTO() }
        } catch (e: Exception) {
            logger.error("Failed to find metadata policy for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Deletes a metadata policy record associated with the given account and ID.
     *
     * @param account The account associated with the metadata policy to be deleted.
     * @param id The unique identifier of the metadata policy record to delete.
     * @return The deleted metadata policy record as a MetadataPolicy object.
     * @throws NotFoundException If the metadata policy record is not found or does not belong to
     * the given account.
     * @throws Exception If an unexpected error occurs during the operation.
     */
    fun deletePolicy(account: Account, id: String): MetadataPolicy {
        logger.info("Deleting metadata policy ID: $id for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val policy =
                Persistence.metadataPolicyQueries.findById(id).executeAsOneOrNull()
                    ?: throw NotFoundException(
                        Constants.ENTITY_CONFIGURATION_METADATA_POLICY_NOT_FOUND
                    )
                        .also { logger.error("Metadata policy not found with ID: $id") }

            if (policy.account_id != account.id) {
                logger.error(
                    "Metadata policy ID: $id does not belong to account: ${account.username}"
                )
                throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_POLICY_NOT_FOUND)
            }

            val deletedPolicy =
                Persistence.metadataPolicyQueries.delete(id).executeAsOneOrNull()
                    ?: throw NotFoundException(
                        Constants.ENTITY_CONFIGURATION_METADATA_POLICY_NOT_FOUND
                    )
                        .also {
                            logger.error("Failed to delete metadata policy ID: $id")
                        }

            logger.info("Successfully deleted metadata policy ID: $id")
            return deletedPolicy.toDTO()
        } catch (e: Exception) {
            logger.error(
                "Failed to delete metadata policy ID: $id for account: ${account.username}",
                e
            )
            throw e
        }
    }
}

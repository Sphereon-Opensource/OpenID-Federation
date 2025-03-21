package com.sphereon.oid.fed.services

import com.sphereon.crypto.kms.IKeyManagementSystem
import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO
import com.sphereon.oid.fed.services.mappers.toDTOs
import com.sphereon.oid.fed.services.mappers.toJsonString
import com.sphereon.oid.fed.services.mappers.toJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.persistence.models.Subordinate as SubordinateEntity
import com.sphereon.oid.fed.persistence.models.SubordinateMetadata as SubordinateMetadataEntity

/**
 * Service class for managing subordinate entities, their associated data,
 * and their interactions with an account.
 *
 * This service provides methods to handle CRUD operations for subordinates,
 * manage subordinate-related keys (JWKs), metadata, and statements, as well as
 * interact with external resources when necessary. Designed to streamline the
 * integration and lifecycle management of subordinates within the context of
 * an account.
 */
class SubordinateService(
    private val accountService: AccountService,
    private val jwkService: JwkService,
    private val kmsProvider: IKeyManagementSystem
) {
    /**
     * Logger instance used to log messages and debug within the `SubordinateService` context.
     * This logger is configured with a specific tag, "SubordinateService", to identify
     * log messages originating from this service.
     */
    private val logger = Logger.tag("SubordinateService")

    /**
     * Represents the persistence mechanism used for managing subordinate-related queries.
     * This property is utilized within the SubordinateService class to interact with and
     * perform database operations related to subordinate entities.
     */
    private val subordinateQueries = Persistence.subordinateQueries

    /**
     * Provides access to database queries related to subordinate JSON Web Keys (JWKs).
     * This property is utilized for managing subordinate-specific JWK data within persistence layers.
     */
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    /**
     * Provides access to database queries related to subordinate statements.
     * This property is used internally within the SubordinateService class
     * to interact with the persistence layer for operations concerning subordinate statements.
     */
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    /**
     * Finds the list of subordinate entities associated with a given account.
     *
     * @param account The account whose subordinates need to be retrieved. Includes details such as ID and username.
     * @return An array of SubordinateEntity objects associated with the specified account.
     */
    fun findSubordinatesByAccount(account: Account): Array<Subordinate> {
        val subordinates = subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
        logger.debug("Found ${subordinates.size} subordinates for account: ${account.username}")
        return subordinates.toDTOs()
    }

    /**
     * Retrieves the identifiers of subordinates associated with the given account as an array of strings.
     *
     * @param account The account for which the subordinate identifiers are to be retrieved.
     * @return An array of strings representing the identifiers of the subordinates associated with the account.
     */
    fun findSubordinatesByAccountAsArray(account: Account): Array<String> {
        val subordinates = findSubordinatesByAccount(account)
        return subordinates.map { it.identifier }.toTypedArray()
    }

    /**
     * Deletes a subordinate associated with the given account.
     *
     * This method locates a subordinate by its identifier and validates if it belongs to the specified account.
     * If the subordinate is not found or doesn't belong to the account, a `NotFoundException` is thrown.
     * Upon successful deletion, the subordinate entity is returned.
     *
     * @param account The account associated with the subordinate to be deleted.
     * @param id The unique identifier of the subordinate to be deleted.
     * @return The deleted subordinate entity.
     * @throws NotFoundException If the subordinate does not exist or belongs to a different account.
     * @throws Exception For any other errors encountered during the delete operation.
     */
    fun deleteSubordinate(account: Account, id: String): Subordinate {
        logger.info("Attempting to delete subordinate ID: $id for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $id does not belong to account: ${account.username}")
                throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            }

            val deletedSubordinate = subordinateQueries.delete(subordinate.id).executeAsOne()
            logger.info("Successfully deleted subordinate ID: $id")
            return deletedSubordinate.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate ID: $id", e)
            throw e
        }
    }

    /**
     * Creates a new subordinate entity associated with the provided account.
     * Validates if a subordinate with the given identifier already exists before creation.
     *
     * @param account The account to which the subordinate will be associated.
     * @param subordinateDTO The data transfer object containing information required to create the subordinate.
     * @return The created subordinate entity.
     * @throws EntityAlreadyExistsException If a subordinate with the same identifier already exists for the given account.
     * @throws Exception If an error occurs during the creation process.
     */
    fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): Subordinate {
        logger.info("Creating new subordinate for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            logger.debug("Checking if subordinate already exists with identifier: ${subordinateDTO.identifier}")
            val subordinateAlreadyExists = subordinateQueries
                .findByAccountIdAndIdentifier(account.id, subordinateDTO.identifier)
                .executeAsList()
            if (subordinateAlreadyExists.isNotEmpty()) {
                logger.warn("Subordinate already exists with identifier: ${subordinateDTO.identifier}")
                throw EntityAlreadyExistsException(Constants.SUBORDINATE_ALREADY_EXISTS)
            }

            val createdSubordinate = subordinateQueries.create(account.id, subordinateDTO.identifier).executeAsOne()
            logger.info("Successfully created subordinate with ID: ${createdSubordinate.id}")
            return createdSubordinate.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to create subordinate for account: ${account.username}", e)
            throw e
        }
    }

    /**
     * Generates a subordinate statement for a specified subordinate ID and account.
     *
     * @param account The account associated with the subordinate statement.
     * @param id The unique identifier of the subordinate for which the statement is being generated.
     * @return The constructed subordinate statement containing the subordinate's information, JWKs, metadata, and other properties.
     * @throws NotFoundException if the subordinate is not found.
     * @throws Exception for any runtime errors occurring during the process.
     */
    fun getSubordinateStatement(account: Account, id: String): SubordinateStatement {
        logger.info("Generating subordinate statement for ID: $id, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")
            val subordinateJwks = subordinateJwkQueries
                .findBySubordinateId(subordinate.id)
                .executeAsList()
                .map { it.toJwk() }
            logger.debug("Found ${subordinateJwks.size} JWKs for subordinate")
            val subordinateMetadataList = Persistence.subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()
            logger.debug("Found ${subordinateMetadataList.size} metadata entries")

            val statement = buildSubordinateStatement(account, subordinate, subordinateJwks, subordinateMetadataList)
            logger.info("Successfully generated subordinate statement")
            return statement
        } catch (e: Exception) {
            logger.error("Failed to generate subordinate statement for ID: $id", e)
            throw e
        }
    }

    /**
     * Builds a SubordinateStatement object for a given account and subordinate entity.
     *
     * @param account The account associated with the subordinate.
     * @param subordinate The subordinate entity for which the statement is being built.
     * @param subordinateJwks A list of JWKs (JSON Web Keys) associated with the subordinate.
     * @param subordinateMetadataList A list of metadata entries for the subordinate.
     * @return A SubordinateStatement object representing the built statement.
     */
    private fun buildSubordinateStatement(
        account: Account,
        subordinate: SubordinateEntity,
        subordinateJwks: List<Jwk>,
        subordinateMetadataList: List<SubordinateMetadataEntity>
    ): SubordinateStatement {
        logger.debug("Building subordinate statement")
        val accountIdentifier = accountService.getAccountIdentifierByAccount(account)
        val currentTimeSeconds = (System.currentTimeMillis() / 1000).toInt()
        val expirationTime = currentTimeSeconds + 3600 * 24 * 365

        check(accountIdentifier.isNotEmpty()) { "Account identifier is empty" }

        val statement = SubordinateStatementObjectBuilder()
            .iss(accountIdentifier)
            .sub(subordinate.identifier)
            .iat(currentTimeSeconds)
            .exp(expirationTime)
            .sourceEndpoint("$accountIdentifier/fetch?sub=${subordinate.identifier}")

        subordinateJwks.forEach {
            logger.debug("Adding JWK to statement")
            statement.jwks(it)
        }

        subordinateMetadataList.forEach {
            logger.debug("Adding metadata entry with key: ${it.key}")
            val metadataJson: JsonObject = Json.parseToJsonElement(it.metadata).jsonObject
            statement.metadata(Pair(it.key, metadataJson))
        }

        return statement.build()
    }

    /**
     * Publishes a subordinate statement for the specified subordinate ID and account.
     * Depending on the `dryRun` flag, the operation might only simulate the publishing process without persistence.
     *
     * @param account The account associated with the subordinate statement.
     * @param id The unique identifier of the subordinate whose statement is to be published.
     * @param dryRun Indicates whether the operation should simulate publishing without persisting (default is false).
     * @return A signed JWT representing the subordinate statement.
     * @throws IllegalArgumentException If no keys are found for the account.
     * @throws Exception If an error occurs during the subordinate statement publishing process.
     */
    suspend fun publishSubordinateStatement(account: Account, id: String, dryRun: Boolean? = false, kmsKeyRef: String? = null, kid: String? = null): String {
        logger.info("Publishing subordinate statement for ID: $id, account: ${account.username} (dryRun: $dryRun)")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinateStatement = getSubordinateStatement(account, id)
            logger.debug("Generated subordinate statement with subject: ${subordinateStatement.sub}")

            val keys = jwkService.getAssertedKeysForAccount(account, includeRevoked = false, kmsKeyRef = kmsKeyRef, kid = kid)
            val key = keys[0]
            logger.debug("Using key with key ref: ${key.kmsKeyRef} and ID: ${key.kid}")

            val jwtService = JwtService(kmsProvider)
            val jwt = jwtService.signSerializable(
                payload = subordinateStatement,
                header = JwtHeader(typ = "entity-statement+jwt", kid = key.kid),
                kid = key.kid,
                kmsKeyRef = key.kmsKeyRef,
            )
            logger.debug("Successfully signed subordinate statement")

            if (dryRun == true) {
                logger.info("Dry run completed, returning JWT without persistence")
                return jwt
            }
            val accountIdentifier = accountService.getAccountIdentifierByAccount(account)
            val persistedStatement = subordinateStatementQueries.create(
                subordinate_id = id,
                iss = accountIdentifier,
                sub = subordinateStatement.sub,
                statement = jwt,
                expires_at = subordinateStatement.exp.toLong()
            ).executeAsOne()
            logger.info("Successfully persisted subordinate statement with ID: ${persistedStatement.id}")
            return jwt
        } catch (e: Exception) {
            logger.error("Failed to publish subordinate statement for ID: $id", e)
            throw e
        }
    }

    /**
     * Creates a new subordinate JSON Web Key (JWK) for a specific subordinate associated with the given account.
     *
     * This method validates the subordinate's existence and its association with the provided account. Upon validation,
     * the JWK is created and logged. If any errors occur during the process, they are logged, and the exception is
     * propagated.
     *
     * @param account The account to which the subordinate is associated.
     * @param id The unique ID of the subordinate for which the JWK will be created.
     * @param jwk The JSON Web Key to be associated with the subordinate.
     * @return The newly created subordinate JWK.
     * @throws NotFoundException If the subordinate does not exist or does not belong to the given account.
     * @throws Exception If an unexpected error occurs during the creation process.
     */
    fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): SubordinateJwk {
        logger.info("Creating subordinate JWK for subordinate ID: $id, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $id does not belong to account: ${account.username}")
                throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            }

            val createdJwk = subordinateJwkQueries.create(key = jwk.toJsonString(), subordinate_id = subordinate.id)
                .executeAsOne()
            logger.info("Successfully created subordinate JWK with ID: ${createdJwk.id}")
            return createdJwk.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to create subordinate JWK for subordinate ID: $id", e)
            throw e
        }
    }

    /**
     * Retrieves an array of JSON Web Keys (JWKs) associated with a specific subordinate account ID.
     *
     * @param account The account performing the operation. Contains details such as ID and username.
     * @param id The unique identifier for the subordinate whose JWKs are to be retrieved.
     * @return An array of [SubordinateJwk] objects containing the JWKs associated with the specified subordinate.
     * @throws NotFoundException If the subordinate is not found.
     * @throws Exception If any other error occurs during the retrieval process.
     */
    fun getSubordinateJwks(account: Account, id: String): Array<SubordinateJwk> {
        logger.info("Retrieving JWKs for subordinate ID: $id, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val jwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList().map { it.toDTO() }
                .toTypedArray()
            logger.info("Found ${jwks.size} JWKs for subordinate ID: $id")
            return jwks
        } catch (e: Exception) {
            logger.error("Failed to retrieve subordinate JWKs for subordinate ID: $id", e)
            throw e
        }
    }

    /**
     * Deletes a subordinate JSON Web Key (JWK) associated with a specified subordinate and account.
     *
     * @param account The account initiating the deletion, must match the subordinate's owning account.
     * @param id The unique identifier of the subordinate for which the JWK is being deleted.
     * @param jwkId The unique identifier of the JWK to be deleted.
     * @return The deleted SubordinateJwk object.
     * @throws NotFoundException If the subordinate or JWK is not found, or if they do not belong to the specified account.
     * @throws Exception If any other error occurs during the deletion process.
     */
    fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): SubordinateJwk {
        logger.info("Deleting subordinate JWK ID: $jwkId for subordinate ID: $id, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $id does not belong to account: ${account.username}")
                throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            }

            val subordinateJwk = subordinateJwkQueries.findById(jwkId).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_JWK_NOT_FOUND)
            logger.debug("Found JWK with ID: $jwkId")

            if (subordinateJwk.subordinate_id != subordinate.id) {
                logger.warn("JWK ID $jwkId does not belong to subordinate ID: $id")
                throw NotFoundException(Constants.SUBORDINATE_JWK_NOT_FOUND)
            }

            val deletedJwk = subordinateJwkQueries.delete(subordinateJwk.id).executeAsOne()
            logger.info("Successfully deleted subordinate JWK with ID: $jwkId")
            return deletedJwk.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate JWK ID: $jwkId", e)
            throw e
        }
    }


    /**
     * Fetches a subordinate statement based on the provided issuer and subject values.
     *
     * @param iss The issuer of the subordinate statement.
     * @param sub The subject of the subordinate statement.
     * @return The subordinate statement as a string.
     * @throws NotFoundException if no subordinate statement is found for the given issuer and subject.
     * @throws Exception for any other errors encountered during the retrieval process.
     */
    fun fetchSubordinateStatement(iss: String, sub: String): String {
        logger.info("Fetching subordinate statement for issuer: $iss, subject: $sub")
        try {
            val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_STATEMENT_NOT_FOUND)
            logger.debug("Found subordinate statement")
            return statement.statement
        } catch (e: Exception) {
            logger.error("Failed to fetch subordinate statement for issuer: $iss, subject: $sub", e)
            throw e
        }
    }

    /**
     * Retrieves metadata associated with a specific subordinate under the provided account.
     *
     * @param account The account object containing details about the user who owns the subordinate.
     * @param subordinateId The unique identifier of the subordinate for which metadata is being requested.
     * @return An array of SubordinateMetadata objects containing metadata information about the specified subordinate.
     * @throws NotFoundException If no subordinate is found for the given account and subordinate ID.
     * @throws Exception If an error occurs during the process of retrieving metadata.
     */
    fun findSubordinateMetadata(account: Account, subordinateId: String): Array<SubordinateMetadata> {
        logger.info("Finding metadata for subordinate ID: $subordinateId, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
                .executeAsOneOrNull() ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val metadata = Persistence.subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()
                .toTypedArray()
            logger.info("Found ${metadata.size} metadata entries for subordinate ID: $subordinateId")
            return metadata.map { it.toDTO() }.toTypedArray()
        } catch (e: Exception) {
            logger.error("Failed to find subordinate metadata for subordinate ID: $subordinateId", e)
            throw e
        }
    }

    /**
     * Creates a new metadata record for a specified subordinate under the given account.
     *
     * @param account The account associated with the subordinate.
     * @param subordinateId The ID of the subordinate for which metadata is being created.
     * @param key The unique key for the metadata entry.
     * @param metadata The JSON object containing the metadata to store.
     * @return The created SubordinateMetadata object representing the newly stored metadata.
     * @throws NotFoundException If the specified subordinate is not found.
     * @throws EntityAlreadyExistsException If metadata with the same key already exists for the subordinate.
     * @throws IllegalStateException If the creation of the metadata record fails.
     */
    fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonObject
    ): SubordinateMetadata {
        logger.info("Creating metadata for subordinate ID: $subordinateId, account: ${account.username}, key: $key")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
                .executeAsOneOrNull() ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            logger.debug("Checking if metadata already exists for key: $key")
            val metadataAlreadyExists =
                Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateIdAndKey(
                    account.id,
                    subordinateId,
                    key
                )
                    .executeAsOneOrNull()

            if (metadataAlreadyExists != null) {
                logger.warn("Metadata already exists for key: $key")
                throw EntityAlreadyExistsException(Constants.SUBORDINATE_METADATA_ALREADY_EXISTS)
            }

            val createdMetadata =
                Persistence.subordinateMetadataQueries.create(account.id, subordinate.id, key, metadata.toString())
                    .executeAsOneOrNull()
                    ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_SUBORDINATE_METADATA)
            logger.info("Successfully created metadata with ID: ${createdMetadata.id}")

            return createdMetadata.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to create metadata for subordinate ID: $subordinateId, key: $key", e)
            throw e
        }
    }

    /**
     * Deletes a subordinate metadata record associated with a specific account and subordinate.
     *
     * @param account The account object containing the ID and username associated with the operation.
     * @param subordinateId The unique identifier of the subordinate whose metadata is to be deleted.
     * @param id The unique identifier of the metadata record to be deleted.
     * @return The deleted subordinate metadata object.
     * @throws NotFoundException if the account, subordinate, or metadata record is not found.
     * @throws Exception if an unexpected error occurs during the deletion process.
     */
    fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): SubordinateMetadata {
        logger.info("Deleting metadata ID: $id for subordinate ID: $subordinateId, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
                .executeAsOneOrNull() ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val metadata =
                Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateIdAndId(
                    account.id,
                    subordinate.id,
                    id
                ).executeAsOneOrNull() ?: throw NotFoundException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)
            logger.debug("Found metadata entry with key: ${metadata.key}")

            val deletedMetadata = Persistence.subordinateMetadataQueries.delete(metadata.id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_METADATA_NOT_FOUND)
            logger.info("Successfully deleted metadata with ID: $id")

            return deletedMetadata.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id", e)
            throw e
        }
    }
}

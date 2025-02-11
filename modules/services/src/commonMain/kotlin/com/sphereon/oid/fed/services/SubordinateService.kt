package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.openapi.models.SubordinateMetadata
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toBaseJwk
import com.sphereon.oid.fed.services.mappers.toDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.persistence.models.Subordinate as SubordinateEntity
import com.sphereon.oid.fed.persistence.models.SubordinateMetadata as SubordinateMetadataEntity

class SubordinateService(
    private val accountService: AccountService,
    private val jwkService: JwkService,
    private val kmsClient: KmsClient
) {
    private val logger = Logger.tag("SubordinateService")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    fun findSubordinatesByAccount(account: Account): Array<SubordinateEntity> {
        val subordinates = subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
        logger.debug("Found ${subordinates.size} subordinates for account: ${account.username}")
        return subordinates
    }

    fun findSubordinatesByAccountAsArray(account: Account): Array<String> {
        val subordinates = findSubordinatesByAccount(account)
        return subordinates.map { it.identifier }.toTypedArray()
    }

    fun deleteSubordinate(account: Account, id: Int): SubordinateEntity {
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
            return deletedSubordinate
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate ID: $id", e)
            throw e
        }
    }

    fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): SubordinateEntity {
        logger.info("Creating new subordinate for account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            logger.debug("Checking if subordinate already exists with identifier: ${subordinateDTO.identifier}")
            val subordinateAlreadyExists =
                subordinateQueries.findByAccountIdAndIdentifier(account.id, subordinateDTO.identifier).executeAsList()

            if (subordinateAlreadyExists.isNotEmpty()) {
                logger.warn("Subordinate already exists with identifier: ${subordinateDTO.identifier}")
                throw EntityAlreadyExistsException(Constants.SUBORDINATE_ALREADY_EXISTS)
            }

            val createdSubordinate = subordinateQueries.create(account.id, subordinateDTO.identifier).executeAsOne()
            logger.info("Successfully created subordinate with ID: ${createdSubordinate.id}")
            return createdSubordinate
        } catch (e: Exception) {
            logger.error("Failed to create subordinate for account: ${account.username}", e)
            throw e
        }
    }

    fun getSubordinateStatement(account: Account, id: Int): SubordinateStatement {
        logger.info("Generating subordinate statement for ID: $id, account: ${account.username}")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val subordinateJwks =
                subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList().map { it.toBaseJwk() }
            logger.debug("Found ${subordinateJwks.size} JWKs for subordinate")

            val subordinateMetadataList =
                Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(account.id, subordinate.id)
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

    private fun buildSubordinateStatement(
        account: Account,
        subordinate: SubordinateEntity,
        subordinateJwks: List<BaseJwk>,
        subordinateMetadataList: List<SubordinateMetadataEntity>
    ): SubordinateStatement {
        logger.debug("Building subordinate statement")
        val statement = SubordinateStatementObjectBuilder()
            .iss(accountService.getAccountIdentifierByAccount(account))
            .sub(subordinate.identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .sourceEndpoint(
                accountService.getAccountIdentifierByAccount(account) + "/fetch?sub=" + subordinate.identifier
            )

        subordinateJwks.forEach {
            logger.debug("Adding JWK to statement")
            statement.jwks(it)
        }

        subordinateMetadataList.forEach {
            logger.debug("Adding metadata entry with key: ${it.key}")
            statement.metadata(
                Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject)
            )
        }

        return statement.build()
    }

    fun publishSubordinateStatement(account: Account, id: Int, dryRun: Boolean? = false): String {
        logger.info("Publishing subordinate statement for ID: $id, account: ${account.username} (dryRun: $dryRun)")
        try {
            logger.debug("Using account with ID: ${account.id}")

            val subordinateStatement = getSubordinateStatement(account, id)
            logger.debug("Generated subordinate statement with subject: ${subordinateStatement.sub}")

            val keys = jwkService.getKeys(account)
            logger.debug("Found ${keys.size} keys for account")

            if (keys.isEmpty()) {
                logger.error("No keys found for account: ${account.username}")
                throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
            }

            val key = keys[0].kid
            logger.debug("Using key with ID: $key")

            val jwt = kmsClient.sign(
                payload = Json.encodeToJsonElement(
                    SubordinateStatement.serializer(),
                    subordinateStatement
                ).jsonObject,
                header = JwtHeader(typ = "entity-statement+jwt", kid = key!!),
                keyId = key
            )
            logger.debug("Successfully signed subordinate statement")

            if (dryRun == true) {
                logger.info("Dry run completed, returning JWT without persistence")
                return jwt
            }

            val statement = subordinateStatementQueries.create(
                subordinate_id = id,
                iss = accountService.getAccountIdentifierByAccount(account),
                sub = subordinateStatement.sub,
                statement = jwt,
                expires_at = subordinateStatement.exp.toLong(),
            ).executeAsOne()
            logger.info("Successfully persisted subordinate statement with ID: ${statement.id}")

            return jwt
        } catch (e: Exception) {
            logger.error("Failed to publish subordinate statement for ID: $id", e)
            throw e
        }
    }

    fun createSubordinateJwk(account: Account, id: Int, jwk: JsonObject): SubordinateJwk {
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

            val createdJwk = subordinateJwkQueries.create(key = jwk.toString(), subordinate_id = subordinate.id)
                .executeAsOne()
            logger.info("Successfully created subordinate JWK with ID: ${createdJwk.id}")
            return createdJwk.toDTO()
        } catch (e: Exception) {
            logger.error("Failed to create subordinate JWK for subordinate ID: $id", e)
            throw e
        }
    }

    fun getSubordinateJwks(account: Account, id: Int): Array<SubordinateJwk> {
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

    fun deleteSubordinateJwk(account: Account, id: Int, jwkId: Int): SubordinateJwk {
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

    fun findSubordinateMetadata(account: Account, subordinateId: Int): Array<SubordinateMetadata> {
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

    fun createMetadata(
        account: Account,
        subordinateId: Int,
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

    fun deleteSubordinateMetadata(account: Account, subordinateId: Int, id: Int): SubordinateMetadata {
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
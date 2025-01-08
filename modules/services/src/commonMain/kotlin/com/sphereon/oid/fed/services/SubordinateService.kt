package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.SubordinateJwkDto
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataDTO
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import com.sphereon.oid.fed.persistence.models.SubordinateMetadata
import com.sphereon.oid.fed.services.extensions.toJwk
import com.sphereon.oid.fed.services.extensions.toSubordinateAdminJwkDTO
import com.sphereon.oid.fed.services.extensions.toSubordinateMetadataDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SubordinateService {
    private val logger = Logger.tag("SubordinateService")
    private val accountService = AccountService()
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries
    private val kmsClient = KmsService.getKmsClient()
    private val keyService = KeyService()

    fun findSubordinatesByAccount(accountUsername: String): Array<Subordinate> {
        logger.debug("Finding subordinates for account: $accountUsername")
        val account = accountQueries.findByUsername(accountUsername).executeAsOne()
        logger.debug("Found account with ID: ${account.id}")

        val subordinates = subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
        logger.info("Found ${subordinates.size} subordinates for account: $accountUsername")
        return subordinates
    }

    fun findSubordinatesByAccountAsArray(accountUsername: String): Array<String> {
        logger.debug("Finding subordinate identifiers for account: $accountUsername")
        val subordinates = findSubordinatesByAccount(accountUsername)
        logger.debug("Converting ${subordinates.size} subordinates to identifier array")
        return subordinates.map { it.identifier }.toTypedArray()
    }

    fun deleteSubordinate(accountUsername: String, id: Int): Subordinate {
        logger.info("Attempting to delete subordinate ID: $id for account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $id does not belong to account: $accountUsername")
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

    fun createSubordinate(accountUsername: String, subordinateDTO: CreateSubordinateDTO): Subordinate {
        logger.info("Creating new subordinate for account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

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
            logger.error("Failed to create subordinate for account: $accountUsername", e)
            throw e
        }
    }

    fun getSubordinateStatement(accountUsername: String, id: Int): SubordinateStatement {
        logger.info("Generating subordinate statement for ID: $id, account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val subordinateJwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
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

    // Continue with similar logging patterns for other methods...

    private fun buildSubordinateStatement(
        account: Account,
        subordinate: Subordinate,
        subordinateJwks: List<SubordinateJwk>,
        subordinateMetadataList: List<SubordinateMetadata>
    ): SubordinateStatement {
        logger.debug("Building subordinate statement")
        val statement = SubordinateStatementObjectBuilder()
            .iss(accountService.getAccountIdentifier(account.username))
            .sub(subordinate.identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .sourceEndpoint(
                accountService.getAccountIdentifier(account.username) + "/fetch?sub=" + subordinate.identifier
            )

        subordinateJwks.forEach {
            logger.debug("Adding JWK to statement")
            statement.jwks(it.toJwk())
        }

        subordinateMetadataList.forEach {
            logger.debug("Adding metadata entry with key: ${it.key}")
            statement.metadata(
                Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject)
            )
        }

        return statement.build()
    }

    fun publishSubordinateStatement(accountUsername: String, id: Int, dryRun: Boolean? = false): String {
        logger.info("Publishing subordinate statement for ID: $id, account: $accountUsername (dryRun: $dryRun)")
        try {
            val account = accountService.getAccountByUsername(accountUsername)
            logger.debug("Found account with ID: ${account.id}")

            val subordinateStatement = getSubordinateStatement(accountUsername, id)
            logger.debug("Generated subordinate statement with subject: ${subordinateStatement.sub}")

            val keys = keyService.getKeys(account.id)
            logger.debug("Found ${keys.size} keys for account")

            if (keys.isEmpty()) {
                logger.error("No keys found for account: $accountUsername")
                throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
            }

            val key = keys[0].kid
            logger.debug("Using key with ID: $key")

            val jwt = kmsClient.sign(
                payload = Json.encodeToJsonElement(
                    SubordinateStatement.serializer(),
                    subordinateStatement
                ).jsonObject,
                header = JWTHeader(typ = "entity-statement+jwt", kid = key!!),
                keyId = key
            )
            logger.debug("Successfully signed subordinate statement")

            if (dryRun == true) {
                logger.info("Dry run completed, returning JWT without persistence")
                return jwt
            }

            val statement = subordinateStatementQueries.create(
                subordinate_id = id,
                iss = accountService.getAccountIdentifier(account.username),
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

    fun createSubordinateJwk(accountUsername: String, id: Int, jwk: JsonObject): SubordinateJwkDto {
        logger.info("Creating subordinate JWK for subordinate ID: $id, account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $id does not belong to account: $accountUsername")
                throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            }

            val createdJwk = subordinateJwkQueries.create(key = jwk.toString(), subordinate_id = subordinate.id)
                .executeAsOne()
                .toSubordinateAdminJwkDTO()
            logger.info("Successfully created subordinate JWK with ID: ${createdJwk.id}")
            return createdJwk
        } catch (e: Exception) {
            logger.error("Failed to create subordinate JWK for subordinate ID: $id", e)
            throw e
        }
    }

    fun getSubordinateJwks(accountUsername: String, id: Int): Array<SubordinateJwkDto> {
        logger.info("Retrieving JWKs for subordinate ID: $id, account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val jwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
                .map { it.toSubordinateAdminJwkDTO() }.toTypedArray()
            logger.info("Found ${jwks.size} JWKs for subordinate ID: $id")
            return jwks
        } catch (e: Exception) {
            logger.error("Failed to retrieve subordinate JWKs for subordinate ID: $id", e)
            throw e
        }
    }

    fun deleteSubordinateJwk(accountUsername: String, subordinateId: Int, id: Int): SubordinateJwk {
        logger.info("Deleting subordinate JWK ID: $id for subordinate ID: $subordinateId, account: $accountUsername")
        try {
            val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            if (subordinate.account_id != account.id) {
                logger.warn("Subordinate ID $subordinateId does not belong to account: $accountUsername")
                throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            }

            val subordinateJwk = subordinateJwkQueries.findById(id).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.SUBORDINATE_JWK_NOT_FOUND)
            logger.debug("Found JWK with ID: $id")

            if (subordinateJwk.subordinate_id != subordinate.id) {
                logger.warn("JWK ID $id does not belong to subordinate ID: $subordinateId")
                throw NotFoundException(Constants.SUBORDINATE_JWK_NOT_FOUND)
            }

            val deletedJwk = subordinateJwkQueries.delete(subordinateJwk.id).executeAsOne()
            logger.info("Successfully deleted subordinate JWK with ID: $id")
            return deletedJwk
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate JWK ID: $id", e)
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

    fun findSubordinateMetadata(
        accountUsername: String,
        subordinateId: Int
    ): Array<SubordinateMetadataDTO> {
        logger.info("Finding metadata for subordinate ID: $subordinateId, account: $accountUsername")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

            val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
                .executeAsOneOrNull() ?: throw NotFoundException(Constants.SUBORDINATE_NOT_FOUND)
            logger.debug("Found subordinate with identifier: ${subordinate.identifier}")

            val metadata = Persistence.subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()
                .map { it.toSubordinateMetadataDTO() }
                .toTypedArray()
            logger.info("Found ${metadata.size} metadata entries for subordinate ID: $subordinateId")
            return metadata
        } catch (e: Exception) {
            logger.error("Failed to find subordinate metadata for subordinate ID: $subordinateId", e)
            throw e
        }
    }

    fun createMetadata(
        accountUsername: String,
        subordinateId: Int,
        key: String,
        metadata: JsonObject
    ): SubordinateMetadataDTO {
        logger.info("Creating metadata for subordinate ID: $subordinateId, account: $accountUsername, key: $key")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

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

            return createdMetadata.toSubordinateMetadataDTO()
        } catch (e: Exception) {
            logger.error("Failed to create metadata for subordinate ID: $subordinateId, key: $key", e)
            throw e
        }
    }

    fun deleteSubordinateMetadata(
        accountUsername: String,
        subordinateId: Int,
        id: Int
    ): SubordinateMetadataDTO {
        logger.info("Deleting metadata ID: $id for subordinate ID: $subordinateId, account: $accountUsername")
        try {
            val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
                ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)
            logger.debug("Found account with ID: ${account.id}")

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

            return deletedMetadata.toSubordinateMetadataDTO()
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id", e)
            throw e
        }
    }

    fun fetchSubordinateStatementByUsernameAndSubject(username: String, sub: String): String {
        logger.info("Fetching subordinate statement for username: $username, subject: $sub")
        try {
            val account = accountQueries.findByUsername(username).executeAsOne()
            logger.debug("Found account with ID: ${account.id}")

            val accountIss = accountService.getAccountIdentifier(account.username)
            logger.debug("Generated issuer identifier: $accountIss")

            val subordinateStatement =
                Persistence.subordinateStatementQueries.findByIssAndSub(accountIss, sub).executeAsOneOrNull()
                    ?: throw NotFoundException(Constants.SUBORDINATE_STATEMENT_NOT_FOUND)
            logger.debug("Found subordinate statement")

            return subordinateStatement.statement
        } catch (e: Exception) {
            logger.error("Failed to fetch subordinate statement for username: $username, subject: $sub", e)
            throw e
        }
    }
}

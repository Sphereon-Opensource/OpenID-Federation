package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.TrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkObject
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.mappers.toTrustMarkDTO
import com.sphereon.oid.fed.services.mappers.toTrustMarkTypeDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TrustMarkService {
    private val logger = Logger.tag("TrustMarkService")
    private val trustMarkQueries = Persistence.trustMarkQueries
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries
    private val kmsClient = KmsService.getKmsClient()
    private val keyService = KeyService()

    fun createTrustMarkType(
        username: String,
        createDto: CreateTrustMarkTypeDTO,
        accountService: AccountService
    ): TrustMarkTypeDTO {
        logger.info("Creating trust mark type ${createDto.identifier} for username: $username")
        val account = accountService.getAccountByUsername(username)
        logger.debug("Found account with ID: ${account.id}")

        this.validateTrustMarkTypeIdentifierDoesNotExist(account.id, createDto.identifier)

        val createdType = trustMarkTypeQueries.create(
            account_id = account.id,
            identifier = createDto.identifier
        ).executeAsOne()
        logger.info("Successfully created trust mark type with ID: ${createdType.id}")

        return createdType.toTrustMarkTypeDTO()
    }

    private fun validateTrustMarkTypeIdentifierDoesNotExist(accountId: Int, identifier: String?) {
        if (identifier != null) {
            logger.debug("Validating identifier uniqueness for account ID: $accountId, identifier: $identifier")
            val trustMarkAlreadyExists = trustMarkTypeQueries.findByAccountIdAndIdentifier(accountId, identifier)
                .executeAsOneOrNull()

            if (trustMarkAlreadyExists != null) {
                logger.error("Trust mark type already exists with identifier: $identifier")
                throw EntityAlreadyExistsException("A trust mark type with the given identifier already exists for this account.")
            }
        }
    }

    fun findAllByAccount(accountId: Int): List<TrustMarkTypeDTO> {
        logger.debug("Finding all trust mark types for account ID: $accountId")
        val types = trustMarkTypeQueries.findByAccountId(accountId).executeAsList()
            .map { it.toTrustMarkTypeDTO() }
        logger.debug("Found ${types.size} trust mark types")
        return types
    }

    fun findById(accountId: Int, id: Int): TrustMarkTypeDTO {
        logger.debug("Finding trust mark type ID: $id for account ID: $accountId")
        val definition = trustMarkTypeQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.").also {
                logger.error("Trust mark type not found with ID: $id")
            }
        return definition.toTrustMarkTypeDTO()
    }

    fun deleteTrustMarkType(accountId: Int, id: Int): TrustMarkTypeDTO {
        logger.info("Deleting trust mark type ID: $id for account ID: $accountId")
        trustMarkTypeQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.").also {
                logger.error("Trust mark type not found with ID: $id")
            }

        val deletedType = trustMarkTypeQueries.delete(id).executeAsOne()
        logger.info("Successfully deleted trust mark type ID: $id")
        return deletedType.toTrustMarkTypeDTO()
    }

    fun getIssuersForTrustMarkType(accountId: Int, trustMarkTypeId: Int): List<String> {
        logger.debug("Getting issuers for trust mark type ID: $trustMarkTypeId, account ID: $accountId")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        val issuers = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .map { it.issuer_identifier }
        logger.debug("Found ${issuers.size} issuers")
        return issuers
    }

    fun addIssuerToTrustMarkType(accountId: Int, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        logger.info("Adding issuer $issuerIdentifier to trust mark type ID: $trustMarkTypeId")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        val existingIssuer = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .any { it.issuer_identifier == issuerIdentifier }

        if (existingIssuer) {
            logger.error("Issuer $issuerIdentifier already exists for trust mark type ID: $trustMarkTypeId")
            throw EntityAlreadyExistsException("Issuer $issuerIdentifier is already associated with the trust mark definition.")
        }

        val created = trustMarkIssuerQueries.create(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
        logger.info("Successfully added issuer $issuerIdentifier")
        return created
    }

    fun removeIssuerFromTrustMarkType(accountId: Int, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        logger.info("Removing issuer $issuerIdentifier from trust mark type ID: $trustMarkTypeId")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .find { it.issuer_identifier == issuerIdentifier }
            ?: throw NotFoundException("Issuer $issuerIdentifier is not associated with the trust mark definition.").also {
                logger.error("Issuer $issuerIdentifier not found for trust mark type ID: $trustMarkTypeId")
            }

        val removed = trustMarkIssuerQueries.delete(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
        logger.info("Successfully removed issuer $issuerIdentifier")
        return removed
    }

    fun getTrustMarksForAccount(accountId: Int): List<TrustMarkDTO> {
        logger.debug("Getting trust marks for account ID: $accountId")
        val trustMarks = trustMarkQueries.findByAccountId(accountId).executeAsList().map { it.toTrustMarkDTO() }
        logger.debug("Found ${trustMarks.size} trust marks")
        return trustMarks
    }

    fun createTrustMark(accountId: Int, body: CreateTrustMarkDTO, accountService: AccountService): TrustMarkDTO {
        logger.info("Creating trust mark for account ID: $accountId, subject: ${body.sub}")
        val account = Persistence.accountQueries.findById(accountId).executeAsOneOrNull()
            ?: throw NotFoundException("Account with ID $accountId not found.").also {
                logger.error("Account not found with ID: $accountId")
            }

        val accountIdentifier = accountService.getAccountIdentifier(account.username)
        logger.debug("Retrieved account identifier: $accountIdentifier")

        val keys = keyService.getKeys(accountId)
        if (keys.isEmpty()) {
            logger.error("No keys found for account ID: $accountId")
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val kid = keys[0].kid
        logger.debug("Using key with KID: $kid")

        val iat = (System.currentTimeMillis() / 1000).toInt()

        val trustMark = TrustMarkObjectBuilder()
            .iss(accountIdentifier)
            .sub(body.sub)
            .id(body.trustMarkTypeIdentifier)
            .iat(iat)
            .logoUri(body.logoUri)
            .ref(body.ref)
            .delegation(body.delegation)

        if (body.exp != null) {
            trustMark.exp(body.exp)
            logger.debug("Setting expiration to: ${body.exp}")
        }

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                TrustMarkObject.serializer(),
                trustMark.build()
            ).jsonObject,
            header = JWTHeader(typ = "trust-mark+jwt", kid = kid!!),
            keyId = kid
        )
        logger.debug("Successfully signed trust mark")

        val created = trustMarkQueries.create(
            account_id = accountId,
            sub = body.sub,
            trust_mark_type_identifier = body.trustMarkTypeIdentifier,
            exp = body.exp,
            iat = iat,
            trust_mark_value = jwt
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${created.id}")

        return created.toTrustMarkDTO()
    }

    fun deleteTrustMark(accountId: Int, id: Int): TrustMarkDTO {
        logger.info("Deleting trust mark ID: $id for account ID: $accountId")
        trustMarkQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark with ID $id not found for account $accountId.").also {
                logger.error("Trust mark not found with ID: $id")
            }

        val deleted = trustMarkQueries.delete(id).executeAsOne().toTrustMarkDTO()
        logger.info("Successfully deleted trust mark ID: $id")
        return deleted
    }

    fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): Boolean {
        logger.debug("Checking trust mark status for account ID: ${account.id}, subject: ${request.sub}")
        val trustMarks = trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(
            account.id,
            request.trustMarkId,
            request.sub
        ).executeAsList()
        logger.debug("Found ${trustMarks.size} matching trust marks")

        if (request.iat != null) {
            logger.debug("Filtering by IAT: ${request.iat}")
            val trustMarkWithIat = trustMarks.find { it.iat == request.iat }
            return trustMarkWithIat != null
        }

        return trustMarks.isNotEmpty()
    }

    fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): Array<String> {
        logger.debug("Getting trust marked subjects for account ID: ${account.id}, trust mark type: ${request.trustMarkId}")
        val subs = if (request.sub != null) {
            logger.debug("Filtering by subject: ${request.sub}")
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(
                account.id, request.trustMarkId, request.sub!!
            ).executeAsList()
        } else {
            trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(
                account.id, request.trustMarkId
            ).executeAsList()
        }
        logger.debug("Found ${subs.size} subjects")
        return subs.toTypedArray()
    }

    fun getTrustMark(account: Account, request: TrustMarkRequest): String {
        logger.debug("Getting trust mark for account ID: ${account.id}, trust mark ID: ${request.trustMarkId}, subject: ${request.sub}")
        val trustMark = trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
            account.id,
            request.trustMarkId,
            request.sub,
        ).executeAsOneOrNull() ?: throw NotFoundException("Trust mark not found.")

        logger.debug("Found trust mark with ID: ${trustMark.id}")
        return trustMark.trust_mark_value
    }
}

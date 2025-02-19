package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.mappers.trustMark.toDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sphereon.oid.fed.persistence.models.TrustMark as TrustMarkEntity

class TrustMarkService(
    private val jwkService: JwkService,
    private val kmsClient: KmsClient,
    private val accountService: AccountService
) {
    private val logger = Logger.tag("TrustMarkService")
    private val trustMarkQueries = Persistence.trustMarkQueries
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    fun createTrustMarkType(
        account: Account,
        createDto: CreateTrustMarkType
    ): TrustMarkType {
        logger.info("Creating trust mark type ${createDto.identifier} for username: ${account.username}")

        this.validateTrustMarkTypeIdentifierDoesNotExist(account, createDto.identifier)

        val createdType = trustMarkTypeQueries.create(
            account_id = account.id,
            identifier = createDto.identifier
        ).executeAsOne()
        logger.info("Successfully created trust mark type with ID: ${createdType.id}")

        return createdType.toDTO()
    }

    private fun validateTrustMarkTypeIdentifierDoesNotExist(account: Account, identifier: String?) {
        if (identifier != null) {
            logger.debug("Validating identifier uniqueness for account ID: $account.id, identifier: $identifier")
            val trustMarkAlreadyExists = trustMarkTypeQueries.findByAccountIdAndIdentifier(account.id, identifier)
                .executeAsOneOrNull()

            if (trustMarkAlreadyExists != null) {
                logger.error("Trust mark type already exists with identifier: $identifier")
                throw EntityAlreadyExistsException("A trust mark type with the given identifier already exists for this account.")
            }
        }
    }

    fun findAllByAccount(account: Account): List<TrustMarkType> {
        logger.debug("Finding all trust mark types for account ID: $account.id")
        val types = trustMarkTypeQueries.findByAccountId(account.id).executeAsList()
            .map { it.toDTO() }
        logger.debug("Found ${types.size} trust mark types")
        return types
    }

    fun findById(account: Account, id: Int): TrustMarkType {
        logger.debug("Finding trust mark type ID: $id for account ID: $account.id")
        val definition = trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $account.id.").also {
                logger.error("Trust mark type not found with ID: $id")
            }
        return definition.toDTO()
    }

    fun deleteTrustMarkType(account: Account, id: Int): TrustMarkType {
        logger.info("Deleting trust mark type ID: $id for account ID: ${account.id}")
        trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account ${account.id}.").also {
                logger.error("Trust mark type not found with ID: $id")
            }

        val deletedType = trustMarkTypeQueries.delete(id).executeAsOne()
        logger.info("Successfully deleted trust mark type ID: $id")
        return deletedType.toDTO()
    }

    fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: Int): List<String> {
        logger.debug("Getting issuers for trust mark type ID: $trustMarkTypeId, account ID: ${account.id}")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account ${account.id}.")
        }

        val issuers = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .map { it.issuer_identifier }
        logger.debug("Found ${issuers.size} issuers")
        return issuers
    }

    fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        logger.info("Adding issuer $issuerIdentifier to trust mark type ID: $trustMarkTypeId")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account ${account.id}.")
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

    fun removeIssuerFromTrustMarkType(
        account: Account,
        trustMarkTypeId: Int,
        issuerIdentifier: String
    ): TrustMarkIssuer {
        logger.info("Removing issuer $issuerIdentifier from trust mark type ID: $trustMarkTypeId")
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            logger.error("Trust mark type not found with ID: $trustMarkTypeId")
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account ${account.id}.")
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

    fun getTrustMarksForAccount(account: Account): List<TrustMark> {
        logger.debug("Getting trust marks for account ID: $account.id")
        val trustMarks = trustMarkQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() }
        logger.debug("Found ${trustMarks.size} trust marks")
        return trustMarks
    }

    fun createTrustMark(
        account: Account,
        body: CreateTrustMark,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): TrustMark {
        logger.info("Creating trust mark for account ID: $account.id, subject: ${body.sub}")

        val keys = jwkService.getKeys(account)
        if (keys.isEmpty()) {
            logger.error("No keys found for account ID: $account.id")
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val kid = keys[0].kid
        logger.debug("Using key with KID: $kid")

        val iat = (currentTimeMillis / 1000).toInt()

        val trustMark = TrustMarkObjectBuilder()
            .iss(accountService.getAccountIdentifierByAccount(account))
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
                TrustMarkPayload.serializer(),
                trustMark.build()
            ).jsonObject,
            header = JwtHeader(typ = "trust-mark+jwt", kid = kid!!),
            keyId = kid
        )
        logger.debug("Successfully signed trust mark")

        val trustMarkEntity: TrustMarkEntity = trustMarkQueries.create(
            account_id = account.id,
            sub = body.sub,
            trust_mark_type_identifier = body.trustMarkTypeIdentifier,
            exp = body.exp,
            iat = iat,
            trust_mark_value = jwt
        ).executeAsOne()
        logger.info("Successfully created trust mark with ID: ${trustMarkEntity.id}")
        return trustMarkEntity.toDTO()
    }

    fun deleteTrustMark(account: Account, id: Int): TrustMark {
        logger.info("Deleting trust mark ID: $id for account ID: $account.id")
        trustMarkQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark with ID $id not found for account $account.id.").also {
                logger.error("Trust mark not found with ID: $id")
            }

        val deleted = trustMarkQueries.delete(id).executeAsOne().toDTO()
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

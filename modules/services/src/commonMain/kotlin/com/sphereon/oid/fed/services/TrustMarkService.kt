package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.TrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkObject
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.UpdateTrustMarkTypeDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.extensions.toTrustMarkDTO
import com.sphereon.oid.fed.services.extensions.toTrustMarkTypeDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TrustMarkService {
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
        val account = accountService.getAccountByUsername(username)

        this.validateTrustMarkTypeIdentifierDoesNotExist(account.id, createDto.identifier)

        val createdType = trustMarkTypeQueries.create(
            account_id = account.id,
            identifier = createDto.identifier
                ?: this.generateUniqueTrustMarkTypeIdentifier(
                    accountService.getAccountIdentifier(username),
                    createDto.name
                ),
            name = createDto.name,
            description = createDto.description
        ).executeAsOne()

        return createdType.toTrustMarkTypeDTO()
    }

    private fun validateTrustMarkTypeIdentifierDoesNotExist(accountId: Int, identifier: String?) {
        if (identifier != null) {
            val trustMarkAlreadyExists = trustMarkTypeQueries.findByAccountIdAndIdentifier(accountId, identifier)
                .executeAsOneOrNull()

            if (trustMarkAlreadyExists != null) {
                throw EntityAlreadyExistsException("A trust mark type with the given identifier already exists for this account.")
            }
        }
    }

    fun findAllByAccount(accountId: Int): List<TrustMarkTypeDTO> {
        return trustMarkTypeQueries.findByAccountId(accountId).executeAsList()
            .map { it.toTrustMarkTypeDTO() }
    }

    fun findById(accountId: Int, id: Int): TrustMarkTypeDTO {
        val definition = trustMarkTypeQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")
        return definition.toTrustMarkTypeDTO()
    }

    fun findByIdentifier(accountId: Int, identifier: String): TrustMarkTypeDTO {
        val definition =
            trustMarkTypeQueries.findByAccountIdAndIdentifier(accountId, identifier).executeAsOneOrNull()
                ?: throw NotFoundException("Trust mark definition with identifier $identifier not found for account $accountId.")
        return definition.toTrustMarkTypeDTO()
    }

    fun updateTrustMarkType(
        accountId: Int,
        id: Int,
        updateDto: UpdateTrustMarkTypeDTO
    ): TrustMarkTypeDTO {
        val existingType = trustMarkTypeQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")

        val updatedType = trustMarkTypeQueries.update(
            name = updateDto.name ?: existingType.name,
            description = updateDto.description ?: existingType.description,
            id = id
        ).executeAsOne()

        return updatedType.toTrustMarkTypeDTO()
    }

    fun deleteTrustMarkType(accountId: Int, id: Int): TrustMarkTypeDTO {
        trustMarkTypeQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark definition with ID $id not found for account $accountId.")

        val deletedType = trustMarkTypeQueries.delete(id).executeAsOne()
        return deletedType.toTrustMarkTypeDTO()
    }

    fun getIssuersForTrustMarkType(accountId: Int, trustMarkTypeId: Int): List<String> {
        // Validate that the trust mark definition belongs to the account
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        // Fetch and return issuers
        return trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .map { it.issuer_identifier }
    }

    fun addIssuerToTrustMarkType(accountId: Int, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        // Validate that the trust mark definition belongs to the account
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        // Check if the issuer already exists
        val existingIssuer = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .any { it.issuer_identifier == issuerIdentifier }

        if (existingIssuer) {
            throw EntityAlreadyExistsException("Issuer $issuerIdentifier is already associated with the trust mark definition.")
        }

        // Add the issuer
        return trustMarkIssuerQueries.create(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
    }

    fun removeIssuerFromTrustMarkType(accountId: Int, trustMarkTypeId: Int, issuerIdentifier: String): TrustMarkIssuer {
        // Validate that the trust mark definition belongs to the account
        val definitionExists = trustMarkTypeQueries.findByAccountIdAndId(accountId, trustMarkTypeId)
            .executeAsOneOrNull()

        if (definitionExists == null) {
            throw NotFoundException("Trust mark definition with ID $trustMarkTypeId not found for account $accountId.")
        }

        // Check if the issuer exists
        trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId)
            .executeAsList()
            .find { it.issuer_identifier == issuerIdentifier }
            ?: throw NotFoundException("Issuer $issuerIdentifier is not associated with the trust mark definition.")

        // Soft-delete the issuer
        return trustMarkIssuerQueries.delete(
            trust_mark_type_id = trustMarkTypeId,
            issuer_identifier = issuerIdentifier
        ).executeAsOne()
    }

    private fun generateUniqueTrustMarkTypeIdentifier(identifier: String, name: String): String {
        val trustMarkTypeBaseIdentifier = "$identifier/trust-mark-types/$name"

        var counter = 2

        var trustMarkTypeIdentifier = trustMarkTypeBaseIdentifier

        while (trustMarkTypeQueries.findByIdentifier(trustMarkTypeIdentifier)
                .executeAsOneOrNull() != null
        ) {
            trustMarkTypeIdentifier = "$trustMarkTypeBaseIdentifier-$counter"
            counter++
        }

        return trustMarkTypeIdentifier
    }

    fun getTrustMarksForAccount(accountId: Int): List<TrustMarkDTO> {
        return trustMarkQueries.findByAccountId(accountId).executeAsList().map { it.toTrustMarkDTO() }
    }

    fun createTrustMark(accountId: Int, body: CreateTrustMarkDTO, accountService: AccountService): TrustMarkDTO {
        val account = Persistence.accountQueries.findById(accountId).executeAsOneOrNull()
            ?: throw NotFoundException("Account with ID $accountId not found.")

        val accountIdentifier = accountService.getAccountIdentifier(account.username)

        val keys = keyService.getKeys(accountId)

        val iat = (System.currentTimeMillis() / 1000).toInt()

        if (keys.isEmpty()) {
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val kid = keys[0].kid

        val trustMark = TrustMarkObjectBuilder()
            .iss(accountIdentifier)
            .sub(body.sub)
            .id(body.trustMarkTypeId)
            .iat(iat)
            .logoUri(body.logoUri)
            .ref(body.ref)
            .delegation(body.delegation)

        if (body.exp != null) {
            trustMark.exp(body.exp)
        }

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                TrustMarkObject.serializer(),
                trustMark.build()
            ).jsonObject,
            header = JWTHeader(typ = "trust-mark+jwt", kid = kid!!),
            keyId = kid
        )

        return trustMarkQueries.create(
            account_id = accountId,
            sub = body.sub,
            trust_mark_type_identifier = body.trustMarkTypeId,
            exp = body.exp,
            iat = iat,
            trust_mark_value = jwt
        ).executeAsOne().toTrustMarkDTO()
    }

    fun deleteTrustMark(accountId: Int, id: Int): TrustMarkDTO {
        trustMarkQueries.findByAccountIdAndId(accountId, id).executeAsOneOrNull()
            ?: throw NotFoundException("Trust mark with ID $id not found for account $accountId.")

        return trustMarkQueries.delete(id).executeAsOne().toTrustMarkDTO()
    }

    fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): Boolean {
        val trustMarks = trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(
            account.id,
            request.sub,
            request.trustMarkId
        ).executeAsList()

        if (request.iat != null) {
            val trustMarkWithIat = trustMarks.find { it.iat == request.iat }
            return trustMarkWithIat != null
        }

        return trustMarks.isNotEmpty()
    }

    fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): Array<String> {
        if (request.sub != null) {
            return trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(
                account.id, request.trustMarkId,
                request.sub!!
            ).executeAsList().toTypedArray()
        }

        return trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(
            account.id, request.trustMarkId
        ).executeAsList().toTypedArray()
    }

    fun getTrustMark(account: Account, request: TrustMarkRequest): String {
        val trustMark = trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(
            account.id,
            request.trustMarkId,
            request.sub,
        ).executeAsOneOrNull() ?: throw NotFoundException("Trust mark not found.")

        return trustMark.trust_mark_value
    }
}

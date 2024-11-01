package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.builder.SubordinateStatementBuilder
import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.SubordinateJwkDto
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataDTO
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import com.sphereon.oid.fed.services.extensions.toJwk
import com.sphereon.oid.fed.services.extensions.toSubordinateAdminJwkDTO
import com.sphereon.oid.fed.services.extensions.toSubordinateMetadataDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SubordinateService {
    private val accountService = AccountService()
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries
    private val kmsClient = KmsService.getKmsClient()
    private val keyService = KeyService()

    fun findSubordinatesByAccount(accountUsername: String): Array<Subordinate> {
        val account = accountQueries.findByUsername(accountUsername).executeAsOne()

        return subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun findSubordinatesByAccountAsArray(accountUsername: String): Array<String> {
        val subordinates = findSubordinatesByAccount(accountUsername)
        return subordinates.map { it.identifier }.toTypedArray()
    }

    fun createSubordinate(accountUsername: String, subordinateDTO: CreateSubordinateDTO): Subordinate {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinateAlreadyExists =
            subordinateQueries.findByAccountIdAndIdentifier(account.id, subordinateDTO.identifier).executeAsList()

        if (subordinateAlreadyExists.isNotEmpty()) {
            throw IllegalArgumentException(Constants.SUBORDINATE_ALREADY_EXISTS)
        }

        return subordinateQueries.create(account.id, subordinateDTO.identifier).executeAsOne()
    }

    fun getSubordinateStatement(accountUsername: String, id: Int): SubordinateStatement {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        val subordinateJwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
        val subordinateMetadataList =
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()

        val subordinateStatement = SubordinateStatementBuilder()
            .iss(accountService.getAccountIdentifier(account.username))
            .sub(subordinate.identifier)
            .iat((System.currentTimeMillis() / 1000).toInt())
            .exp((System.currentTimeMillis() / 1000 + 3600 * 24 * 365).toInt())
            .sourceEndpoint(
                accountService.getAccountIdentifier(account.username) + "/fetch?sub=" + subordinate.identifier
            )

        subordinateJwks.forEach {
            subordinateStatement.jwks(it.toJwk())
        }

        subordinateMetadataList.forEach {
            subordinateStatement.metadata(
                Pair(it.key, Json.parseToJsonElement(it.metadata).jsonObject)
            )
        }

        return subordinateStatement.build()
    }

    fun publishSubordinateStatement(accountUsername: String, id: Int, dryRun: Boolean? = false): String {
        val account = accountService.getAccountByUsername(accountUsername)

        val subordinateStatement = getSubordinateStatement(accountUsername, id)

        val keys = keyService.getKeys(accountUsername)

        if (keys.isEmpty()) {
            throw IllegalArgumentException(Constants.NO_KEYS_FOUND)
        }

        val key = keys[0].kid

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                SubordinateStatement.serializer(),
                subordinateStatement
            ).jsonObject,
            header = JWTHeader(typ = "entity-statement+jwt", kid = key!!),
            keyId = key
        )

        if (dryRun == true) {
            return jwt
        }

        subordinateStatementQueries.create(
            subordinate_id = id,
            iss = accountService.getAccountIdentifier(account.username),
            sub = subordinateStatement.sub,
            statement = jwt,
            expires_at = subordinateStatement.exp.toLong(),
        ).executeAsOne()

        return jwt
    }

    fun createSubordinateJwk(accountUsername: String, id: Int, jwk: JsonObject): SubordinateJwkDto {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        if (subordinate.account_id != account.id) {
            throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)
        }

        return subordinateJwkQueries.create(key = jwk.toString(), subordinate_id = subordinate.id).executeAsOne()
            .toSubordinateAdminJwkDTO()
    }

    fun getSubordinateJwks(accountUsername: String, id: Int): Array<SubordinateJwkDto> {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        return subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
            .map { it.toSubordinateAdminJwkDTO() }.toTypedArray()
    }

    fun deleteSubordinateJwk(accountUsername: String, subordinateId: Int, id: Int): SubordinateJwk {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        if (subordinate.account_id != account.id) {
            throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)
        }

        val subordinateJwk = subordinateJwkQueries.findById(id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_JWK_NOT_FOUND)

        if (subordinateJwk.subordinate_id != subordinate.id) {
            throw IllegalArgumentException(Constants.SUBORDINATE_JWK_NOT_FOUND)
        }

        return subordinateJwkQueries.delete(subordinateJwk.id).executeAsOne()
    }

    fun fetchSubordinateStatement(iss: String, sub: String): String {
        val subordinateStatement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_STATEMENT_NOT_FOUND)

        return subordinateStatement.statement
    }

    fun findSubordinateMetadata(
        accountUsername: String,
        subordinateId: Int
    ): Array<SubordinateMetadataDTO> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull() ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        return Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateId(account.id, subordinate.id)
            .executeAsList()
            .map { it.toSubordinateMetadataDTO() }.toTypedArray()
    }

    fun createMetadata(
        accountUsername: String,
        subordinateId: Int,
        key: String,
        metadata: JsonObject
    ): SubordinateMetadataDTO {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull() ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        val metadataAlreadyExists =
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateIdAndKey(account.id, subordinateId, key)
                .executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            throw IllegalStateException(Constants.SUBORDINATE_METADATA_ALREADY_EXISTS)
        }

        val createdMetadata =
            Persistence.subordinateMetadataQueries.create(account.id, subordinate.id, key, metadata.toString())
                .executeAsOneOrNull()
                ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_SUBORDINATE_METADATA)

        return createdMetadata.toSubordinateMetadataDTO()
    }

    fun deleteSubordinateMetadata(
        accountUsername: String,
        subordinateId: Int,
        id: Int
    ): SubordinateMetadataDTO {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinate = Persistence.subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull() ?: throw IllegalArgumentException(Constants.SUBORDINATE_NOT_FOUND)

        val metadata =
            Persistence.subordinateMetadataQueries.findByAccountIdAndSubordinateIdAndId(
                account.id,
                subordinate.id,
                id
            ).executeAsOneOrNull() ?: throw IllegalArgumentException(Constants.ENTITY_CONFIGURATION_METADATA_NOT_FOUND)

        val deletedMetadata = Persistence.subordinateMetadataQueries.delete(metadata.id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_METADATA_NOT_FOUND)

        return deletedMetadata.toSubordinateMetadataDTO()
    }

    fun fetchSubordinateStatementByUsernameAndSubject(username: String, sub: String): String {
        val account = accountQueries.findByUsername(username).executeAsOne()

        val accountIss = accountService.getAccountIdentifier(account.username)

        val subordinateStatement =
            Persistence.subordinateStatementQueries.findByIssAndSub(accountIss, sub).executeAsOneOrNull()
                ?: throw IllegalArgumentException(Constants.SUBORDINATE_STATEMENT_NOT_FOUND)

        return subordinateStatement.statement
    }
}

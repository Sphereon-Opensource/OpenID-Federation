package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.mappers.toHistoricalKey
import com.sphereon.oid.fed.services.mappers.toJwkAdminDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class KeyService(
    private val kmsClient: KmsClient
) {
    private val logger = Logger.tag("KeyService")
    private val keyQueries = Persistence.keyQueries

    fun createKey(account: Account): JwkAdminDTO {
        logger.info("Creating new key for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        val jwk = kmsClient.generateKeyPair()
        logger.debug("Generated key pair with KID: ${jwk.kid}")

        keyQueries.create(
            account_id = account.id,
            kid = jwk.kid,
            key = Json.encodeToString(JwkAdminDTO.serializer(), jwk),
        ).executeAsOne()
        logger.info("Successfully created key with KID: ${jwk.kid} for account ID: ${account.id}")

        return jwk
    }

    fun getKeys(account: Account): Array<JwkAdminDTO> {
        logger.debug("Retrieving keys for account: ${account.username}")

        val keys = keyQueries.findByAccountId(account.id).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: ${account.id}")
        return keys
    }

    fun revokeKey(account: Account, keyId: Int, reason: String?): JwkAdminDTO {
        logger.info("Attempting to revoke key ID: $keyId for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        var key = keyQueries.findById(keyId).executeAsOne()
        logger.debug("Found key with ID: $keyId")

        if (key.account_id != account.id) {
            logger.error("Key ID: $keyId does not belong to account: ${account.username}")
            throw NotFoundException(Constants.KEY_NOT_FOUND)
        }

        if (key.revoked_at != null) {
            logger.error("Key ID: $keyId is already revoked")
            throw IllegalStateException(Constants.KEY_ALREADY_REVOKED)
        }

        keyQueries.revoke(reason, keyId)
        logger.debug("Revoked key ID: $keyId with reason: ${reason ?: "no reason provided"}")

        key = keyQueries.findById(keyId).executeAsOne()
        logger.info("Successfully revoked key ID: $keyId")

        return key.toJwkAdminDTO()
    }

    private fun getFederationHistoricalKeys(account: Account): Array<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account: ${account.username}")
        val keys = keyQueries.findByAccountId(account.id).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: ${account.id}")

        return keys.map {
            it.toHistoricalKey()
        }.toTypedArray()
    }

    fun getFederationHistoricalKeysJwt(account: Account, accountService: AccountService): String {
        val iss = accountService.getAccountIdentifierByAccount(account)

        val historicalKeysJwkObject = FederationHistoricalKeysResponse(
            iss = iss,
            iat = (System.currentTimeMillis() / 1000).toInt(),
            propertyKeys = getFederationHistoricalKeys(account)
        )

        val keys = getKeys(account)

        if (keys.isEmpty()) {
            logger.error("No keys found for account: ${account.username}")
            throw IllegalArgumentException("The system is in an invalid state.")
        }

        val key = keys[0].kid

        val jwt = kmsClient.sign(
            payload = Json.encodeToJsonElement(
                FederationHistoricalKeysResponse.serializer(),
                historicalKeysJwkObject
            ).jsonObject,
            header = JWTHeader(typ = "jwk-set+jwt", kid = key!!),
            keyId = key
        )

        logger.verbose("Successfully built federation historical keys JWT for username: ${account.username}")
        logger.debug("JWT: $jwt")

        return jwt
    }
}

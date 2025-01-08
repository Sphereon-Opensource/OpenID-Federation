package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.extensions.toHistoricalKey
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class KeyService {
    private val logger = Logger.tag("KeyService")
    private val kmsClient = KmsService.getKmsClient()
    private val accountQueries = Persistence.accountQueries
    private val keyQueries = Persistence.keyQueries

    fun create(accountId: Int): JwkAdminDTO {
        logger.info("Creating new key for account ID: $accountId")
        val account = accountQueries.findById(accountId).executeAsOne()
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

    fun getKeys(accountId: Int): Array<JwkAdminDTO> {
        logger.debug("Retrieving keys for account ID: $accountId")
        val account = accountQueries.findById(accountId).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND).also {
                logger.error("Account not found with ID: $accountId")
            }

        val keys = keyQueries.findByAccountId(account.id).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: $accountId")
        return keys
    }

    fun revokeKey(accountId: Int, keyId: Int, reason: String?): JwkAdminDTO {
        logger.info("Attempting to revoke key ID: $keyId for account ID: $accountId")
        val account = accountQueries.findById(accountId).executeAsOne()
        logger.debug("Found account with ID: ${account.id}")

        var key = keyQueries.findById(keyId).executeAsOne()
        logger.debug("Found key with ID: $keyId")

        if (key.account_id != account.id) {
            logger.error("Key ID: $keyId does not belong to account ID: $accountId")
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

    private fun getFederationHistoricalKeys(accountId: Int): Array<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account ID: $accountId")
        val keys = keyQueries.findByAccountId(accountId).executeAsList().map { it.toJwkAdminDTO() }.toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: $accountId")

        return keys.map {
            it.toHistoricalKey()
        }.toTypedArray()
    }

    fun getFederationHistoricalKeysJwt(account: Account, accountService: AccountService): String {
        val iss = accountService.getAccountIdentifier(account.username)

        val historicalKeysJwkObject = FederationHistoricalKeysResponse(
            iss = iss,
            iat = (System.currentTimeMillis() / 1000).toInt(),
            propertyKeys = getFederationHistoricalKeys(account.id)
        )

        val keys = getKeys(account.id)

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

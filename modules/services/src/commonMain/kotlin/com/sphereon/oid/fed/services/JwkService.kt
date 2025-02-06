package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.mappers.toDTO
import com.sphereon.oid.fed.services.mappers.toHistoricalKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class JwkService(
    private val kmsClient: KmsClient
) {
    private val logger = Logger.tag("KeyService")
    private val jwkQueries = Persistence.jwkQueries

    fun createKey(account: Account): Jwk {
        logger.info("Creating new key for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        val jwk = kmsClient.generateKeyPair()
        logger.debug("Generated key pair with KID: ${jwk.kid}")

        val createdJwk = jwkQueries.create(
            account_id = account.id,
            kid = jwk.kid,
            key = Json.encodeToString(Jwk.serializer(), jwk),
        ).executeAsOne()
        logger.info("Successfully created key with KID: ${jwk.kid} for account ID: ${account.id}")

        return createdJwk.toDTO()
    }

    fun getKeys(account: Account): Array<Jwk> {
        logger.debug("Retrieving keys for account: ${account.username}")

        val keys = jwkQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() }.toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: ${account.id}")
        return keys
    }

    fun revokeKey(account: Account, keyId: Int, reason: String?): Jwk {
        logger.info("Attempting to revoke key ID: $keyId for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        var key = jwkQueries.findById(keyId).executeAsOne()
        logger.debug("Found key with ID: $keyId")

        if (key.account_id != account.id) {
            logger.error("Key ID: $keyId does not belong to account: ${account.username}")
            throw NotFoundException(Constants.KEY_NOT_FOUND)
        }

        try {
            key = jwkQueries.revoke(reason, keyId).executeAsOne()
            logger.debug("Revoked key ID: $keyId with reason: ${reason ?: "no reason provided"}")
            logger.info("Successfully revoked key ID: $keyId")
        } catch (e: Exception) {
            logger.error("Failed to revoke key ID: $keyId due to: ${e.message}")
            throw e
        }

        return key.toDTO()
    }

    private fun getFederationHistoricalKeys(account: Account): Array<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account: ${account.username}")
        val keys = jwkQueries.findByAccountId(account.id).executeAsList()
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
            header = JwtHeader(typ = "jwk-set+jwt", kid = key!!),
            keyId = key
        )

        logger.verbose("Successfully built federation historical keys JWT for username: ${account.username}")
        logger.debug("JWT: $jwt")

        return jwt
    }
}

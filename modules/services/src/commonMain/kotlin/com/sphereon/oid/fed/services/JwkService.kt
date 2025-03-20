package com.sphereon.oid.fed.services

import com.sphereon.crypto.generic.KeyOperations
import com.sphereon.crypto.generic.SignatureAlgorithm
import com.sphereon.crypto.jose.JwaAlgorithm
import com.sphereon.crypto.jose.JwkUse
import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.JwtHeader
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.mappers.toDTO
import com.sphereon.oid.fed.services.mappers.toHistoricalKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service responsible for operations related to JSON Web Keys (JWK).
 *
 * This service includes functionalities to create, manage, revoke, and retrieve keys associated with accounts,
 * as well as generating federated historical keys in JWT format.
 *
 * @constructor Initializes the service with a specific key management system.
 * @param keyManagementSystem The instance responsible for managing cryptographic keys.
 */
class JwkService(
    private val kmsService: KmsService
) {
    private val keyManagementSystem = kmsService.getKmsProvider()
    /**
     * Companion object for the JwkService class.
     * Provides constants and utility functions relevant to the service's operation.
     */
    companion object {
        /**
         * Represents the type of JWT (JSON Web Token) utilized in JWK (JSON Web Key) sets
         * within the `JwkService` class. This constant defines the JWT type as "jwk-set+jwt",
         * which is specifically used to signify the integration of JWTs with JWK sets in
         * key management and cryptographic operations.
         */
        private const val JWT_TYPE = "jwk-set+jwt"
    }

    /**
     * Logger instance used for logging events and debugging information within the `JwkService` class.
     * It is configured with a specific tag ("JwkService") to differentiate logs emitted by this service from others.
     */
    private val logger = Logger.tag("JwkService")

    /**
     * A reference to the JWK (JSON Web Key) related database queries handled via the `Persistence` layer.
     * This allows for operations such as storage, retrieval, and management of JWKs.
     */
    private val jwkQueries = Persistence.jwkQueries

    /**
     * Creates a new JSON Web Key (JWK) for the specified account and stores it in the system.
     *
     * The method generates a new key, assigns it to the provided account, and returns the created JWK object.
     *
     * @param account The account for which a new JWK is being created. It must include the unique account ID and username.
     * @return The created JWK object associated with the specified account.
     */
    suspend fun createKey(account: Account, opts: CreateKeyArgs = CreateKeyArgs()): AccountJwk = withContext(Dispatchers.IO) {
        logger.info("Creating new key for account: ${account.username}, with options: $opts")
        logger.debug("Found account with ID: ${account.id}")
        val (_, kmsKeyRef, use, keyOperations, alg) = opts
        val kms = opts.kms ?: kmsService.getKmsType().name
        check(kmsService.getKmsType().name.lowercase() == kms.lowercase()) { "Provided KMS type $kms does not match configured KMS type ${kmsService.getKmsType().name}" }
        // TODO: Support multiple KMS systems at the same time. That is why we have the KeyManager service in the crypto module

        check(getKeys(account, includeRevoked = false).find { kmsKeyRef !== null && it.kmsKeyRef == kmsKeyRef } == null) { "Key with KMS key ref $kmsKeyRef already exists for account ID: ${account.id}"}
        val generatedJwk = keyManagementSystem.generateKeyAsync(kmsKeyRef, use, keyOperations, alg)
        requireNotNull(generatedJwk.kmsKeyRef) { "Generated key kmsKeyRef cannot be null" }
        requireNotNull(generatedJwk.kid) { "Generated key ID cannot be null" }
        logger.debug("Generated key pair with KID: ${generatedJwk.kid} kms: ${kms} and kmsKeyRef: ${generatedJwk.kmsKeyRef} for account ID: ${account.id}")

        val createdKey = jwkQueries.create(
            account_id = account.id,
            kid = generatedJwk.kid,
            kms_key_ref = generatedJwk.kmsKeyRef,
            kms = kms,
            key = generatedJwk.jose.publicJwk.toJsonString()
        ).executeAsOne()

        logger.info("Successfully created key with KID: ${generatedJwk.kid} for account ID: ${account.id}")
        createdKey.toDTO()
    }

    /**
     * Retrieves the keys associated with a given account.
     *
     * @param account The account for which the keys are to be retrieved.
     * @return An array of AccountJwk objects representing the keys associated with the given account.
     */
    fun getKeys(account: Account, includeRevoked: Boolean = false): Array<AccountJwk> {
        logger.debug("Retrieving keys for account: ${account.username}")
        val keys = jwkQueries.findByAccountId(account.id)
            .executeAsList()
            .filter { includeRevoked || it.revoked_at == null }
            .map { it.toDTO() }
            .toTypedArray()
        logger.debug("Found ${keys.size} keys for account ID: ${account.id}, including revoked keys: $includeRevoked")
        return keys
    }


    /**
     * Retrieves the keys associated with the given account or throws an exception if no keys are found.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param kmsKeyRef A KMS Key reference. Takes precedence over a kid
     * @param kid A kid value. kmsKeyRef always takes precedence
     * @return An array of AccountJwk objects associated with the given account.
     * @throws IllegalArgumentException If no keys are found for the account.
     */
    fun getAssertedKeysForAccount(account: Account, includeRevoked: Boolean = false, kmsKeyRef: String? = null, kid: String? = null): Array<AccountJwk> {
        val keys = getKeys(account, includeRevoked).filter { kmsKeyRef === null || it.kmsKeyRef == kmsKeyRef }.filter { kid === null || it.kid  == kid }.toTypedArray()
        println("Found ${keys.size} keys for account: ${account.username} and filters key ref: ${kmsKeyRef}, kid: $kid")

        if (keys.isEmpty()) {
            logger.error("No keys found for account: ${account.username}")
            throw NotFoundException(Constants.NO_KEYS_FOUND)
        }
        return keys
    }

    /**
     * Revokes a specific key associated with the provided account.
     *
     * @param account The account associated with the key to be revoked.
     * @param keyId The unique identifier of the key to be revoked.
     * @param reason An optional reason for revoking the key.
     * @return The updated `AccountJwk` object representing the revoked key.
     * @throws DataAccessException If an error occurs while accessing the data store.
     * @throws NotFoundException If the key does not belong to the specified account.
     */
    fun revokeKey(account: Account, keyId: Int, reason: String?): AccountJwk {
        logger.info("Attempting to revoke key ID: $keyId for account: ${account.username}")
        logger.debug("Found account with ID: ${account.id}")

        var existingKey = jwkQueries.findById(keyId).executeAsOne()
        logger.debug("Found key with ID: $keyId")

        ensureKeyOwnership(existingKey, account)
        try {
            existingKey = jwkQueries.revoke(reason, keyId).executeAsOne()
            logger.debug("Revoked key ID: $keyId with reason: ${reason ?: "no reason provided"}")
            logger.info("Successfully revoked key ID: $keyId")
        } catch (e: Exception) {
            // Assume DataAccessException represents data access errors
            logger.error("Failed to revoke key ID: $keyId due to: ${e.message}")
            throw e
        }
        return existingKey.toDTO()
    }

    /**
     * Generates and returns a JWT representing the historical federation keys for the specified account.
     *
     * The JWT is created using the account's federation historical keys and signed with the primary key.
     *
     * @param account The account for which the federation historical keys JWT is being generated.
     *                The account should include the necessary information like username and identifier.
     * @param accountService The service used to interact with account-related functionality,
     *                       such as retrieving account identifiers.
     * @return A `String` representing the signed JWT containing the federation historical keys.
     * @throws IllegalStateException If no keys are found for the account or if the primary key lacks an identifier.
     */
    suspend fun getFederationHistoricalKeysJwt(account: Account, accountService: AccountService): String =
        withContext(Dispatchers.IO) {
            val iss = accountService.getAccountIdentifierByAccount(account)
            val federationKeysResponse = FederationHistoricalKeysResponse(
                iss = iss,
                iat = (System.currentTimeMillis() / 1000).toInt(),
                propertyKeys = getFederationHistoricalKeys(account)
            )

            val keys = getKeys(account)
            if (keys.isEmpty()) {
                logger.error("No keys found for account: ${account.username}")
                throw IllegalStateException("The system is in an invalid state: no keys for account.")
            }
            // If the key's kid value is not set, explicitly throw an exception.
            val kid = keys.firstOrNull()?.kid ?: throw IllegalStateException("Primary key has a null identifier.")

            val header = JwtHeader(typ = JWT_TYPE, kid = kid)
            val jwtService = JwtService(keyManagementSystem)
            val jwt = jwtService.signSerializable(federationKeysResponse, header, kid)
            logger.verbose("Successfully built federation historical keys JWT for username: ${account.username}")
            logger.debug("JWT: $jwt")
            jwt
        }

    /**
     * Ensures that the given JSON Web Key (JWK) belongs to the specified account.
     * If the key does not belong to the account, an error is logged, and a NotFoundException is thrown.
     *
     * @param jwk The JSON Web Key to check ownership for.
     * @param account The account to verify ownership against.
     * @throws NotFoundException if the JWK does not belong to the specified account.
     */
    private fun ensureKeyOwnership(jwk: Jwk, account: Account) {
        if (jwk.account_id != account.id) {
            logger.error("Key does not belong to account: ${account.username}")
            throw NotFoundException(Constants.KEY_NOT_FOUND)
        }
    }

    /**
     * Retrieves the historical federation keys associated with a specific account.
     *
     * @param account The account for which the historical federation keys are being retrieved.
     * @return An array of historical keys associated with the given account.
     */
    private fun getFederationHistoricalKeys(account: Account): Array<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account: ${account.username}")
        val records = jwkQueries.findByAccountId(account.id).executeAsList()
        logger.debug("Found ${records.size} keys for account ID: ${account.id}")
        return records.map { it.toHistoricalKey() }.toTypedArray()
    }
}

data class CreateKeyArgs(
    val kms: String? = null,
    val kmsKeyRef: String? = null,
    val use: JwkUse = JwkUse.sig,
    val keyOperations: Array<out KeyOperations> = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY),
    val alg: SignatureAlgorithm = SignatureAlgorithm.ECDSA_SHA256
) {


    companion object {
        fun fromModel(model: CreateKey) = with(model) {
            CreateKeyArgs(
                kms = kms,
                kmsKeyRef = kmsKeyRef,
                alg = SignatureAlgorithm.Static.fromJose(JwaAlgorithm.Static.fromValue(signatureAlgorithm?.value) ?: JwaAlgorithm.ES256)
            )
        }

    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CreateKeyArgs

        if (kms != other.kms) return false
        if (kmsKeyRef != other.kmsKeyRef) return false
        if (use != other.use) return false
        if (!keyOperations.contentEquals(other.keyOperations)) return false
        if (alg != other.alg) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kms?.hashCode() ?: 0
        result = 31 * result + (kmsKeyRef?.hashCode() ?: 0)
        result = 31 * result + use.hashCode()
        result = 31 * result + keyOperations.contentHashCode()
        result = 31 * result + alg.hashCode()
        return result
    }
}
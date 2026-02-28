package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk

/**
 * Service interface responsible for operations related to JSON Web Keys (JWK).
 *
 * This service includes functionalities to create, manage, revoke, and retrieve keys associated with accounts,
 * as well as generating federated historical keys in JWT format.
 *
 * This service aggregates all JWK-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface JwkService {

    /**
     * Creates a new JSON Web Key (JWK) for the specified account.
     *
     * @param account The account for which a new JWK is being created.
     * @param opts Options for key creation.
     * @return FederationResult containing the created AccountJwk or an error.
     */
    suspend fun createKey(account: Account, opts: CreateKeyArgs): FederationResult<AccountJwk>

    /**
     * Retrieves the keys associated with a given account.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @return FederationResult containing an array of AccountJwk or an error.
     */
    suspend fun getKeys(account: Account, includeRevoked: Boolean): FederationResult<Array<AccountJwk>>

    /**
     * Retrieves the keys associated with the given account or returns an error if no keys are found.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @param kmsKeyRef Optional KMS Key reference filter.
     * @param kid Optional kid filter.
     * @return FederationResult containing an array of AccountJwk or an error.
     */
    suspend fun getAssertedKeysForAccount(
        account: Account,
        includeRevoked: Boolean,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<Array<AccountJwk>>

    /**
     * Revokes a specific key associated with the provided account.
     *
     * @param account The account associated with the key to be revoked.
     * @param keyId The unique identifier of the key to be revoked.
     * @param reason An optional reason for revoking the key.
     * @return FederationResult containing the revoked AccountJwk or an error.
     */
    suspend fun revokeKey(account: Account, keyId: String, reason: String?): FederationResult<AccountJwk>

    /**
     * Generates and returns a JWT representing the historical federation keys.
     *
     * @param account The account for which the federation historical keys JWT is being generated.
     * @return FederationResult containing the signed JWT or an error.
     */
    suspend fun getFederationHistoricalKeysJwt(account: Account): FederationResult<String>
}

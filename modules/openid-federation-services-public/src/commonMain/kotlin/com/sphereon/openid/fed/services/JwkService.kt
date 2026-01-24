package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommand
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommandService
import com.sphereon.openid.fed.services.command.jwk.GetAssertedKeysCommand
import com.sphereon.openid.fed.services.command.jwk.GetAssertedKeysCommandService
import com.sphereon.openid.fed.services.command.jwk.GetFederationHistoricalKeysJwtCommand
import com.sphereon.openid.fed.services.command.jwk.GetFederationHistoricalKeysJwtCommandService
import com.sphereon.openid.fed.services.command.jwk.GetKeysCommand
import com.sphereon.openid.fed.services.command.jwk.GetKeysCommandService
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyCommand
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyCommandService

/**
 * Service interface responsible for operations related to JSON Web Keys (JWK).
 *
 * This service includes functionalities to create, manage, revoke, and retrieve keys associated with accounts,
 * as well as generating federated historical keys in JWT format.
 *
 * This service aggregates all JWK-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface JwkService :
    CreateKeyCommandService,
    GetKeysCommandService,
    GetAssertedKeysCommandService,
    RevokeKeyCommandService,
    GetFederationHistoricalKeysJwtCommandService {

    /**
     * Provides access to individual JWK commands for advanced use cases
     * like composition, chaining, or extension-based processing.
     */
    val commands: Commands

    /**
     * Container interface for all JWK-related commands.
     */
    interface Commands {
        val createKey: CreateKeyCommand
        val getKeys: GetKeysCommand
        val getAssertedKeys: GetAssertedKeysCommand
        val revokeKey: RevokeKeyCommand
        val getFederationHistoricalKeysJwt: GetFederationHistoricalKeysJwtCommand
    }

    /**
     * Creates a new JSON Web Key (JWK) for the specified account.
     *
     * @param account The account for which a new JWK is being created.
     * @param opts Options for key creation.
     * @return FederationResult containing the created AccountJwk or an error.
     */
    override suspend fun createKey(account: Account, opts: CreateKeyArgs): FederationResult<AccountJwk>

    /**
     * Retrieves the keys associated with a given account.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @return FederationResult containing an array of AccountJwk or an error.
     */
    override suspend fun getKeys(account: Account, includeRevoked: Boolean): FederationResult<Array<AccountJwk>>

    /**
     * Retrieves the keys associated with the given account or returns an error if no keys are found.
     *
     * @param account The account for which the keys are to be retrieved.
     * @param includeRevoked Whether to include revoked keys.
     * @param kmsKeyRef Optional KMS Key reference filter.
     * @param kid Optional kid filter.
     * @return FederationResult containing an array of AccountJwk or an error.
     */
    override suspend fun getAssertedKeysForAccount(
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
    override suspend fun revokeKey(account: Account, keyId: String, reason: String?): FederationResult<AccountJwk>

    /**
     * Generates and returns a JWT representing the historical federation keys.
     *
     * @param account The account for which the federation historical keys JWT is being generated.
     * @return FederationResult containing the signed JWT or an error.
     */
    override suspend fun getFederationHistoricalKeysJwt(account: Account): FederationResult<String>
}

package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommand
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommandArgs
import com.sphereon.openid.fed.services.command.jwk.GetAssertedKeysArgs
import com.sphereon.openid.fed.services.command.jwk.GetAssertedKeysCommand
import com.sphereon.openid.fed.services.command.jwk.GetFederationHistoricalKeysJwtArgs
import com.sphereon.openid.fed.services.command.jwk.GetFederationHistoricalKeysJwtCommand
import com.sphereon.openid.fed.services.command.jwk.GetKeysArgs
import com.sphereon.openid.fed.services.command.jwk.GetKeysCommand
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyArgs
import com.sphereon.openid.fed.services.command.jwk.RevokeKeyCommand
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of JwkService as a command aggregator.
 *
 * This service aggregates all JWK-related commands and provides both
 * direct method access and command-based access for advanced use cases.
 *
 * All methods delegate to individual commands, enabling:
 * - Command composition and chaining
 * - Cross-cutting concerns via command extensions (audit, caching, auth)
 * - Testability through command mocking
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = JwkService::class)
class JwkServiceImpl(
    private val createKeyCommand: CreateKeyCommand,
    private val getKeysCommand: GetKeysCommand,
    private val getAssertedKeysCommand: GetAssertedKeysCommand,
    private val revokeKeyCommand: RevokeKeyCommand,
    private val getFederationHistoricalKeysJwtCommand: GetFederationHistoricalKeysJwtCommand
) : JwkService {

    override suspend fun createKey(account: Account, opts: CreateKeyArgs): FederationResult<AccountJwk> =
        createKeyCommand.execute(CreateKeyCommandArgs(account, opts)).toFederationResult()

    override suspend fun getKeys(account: Account, includeRevoked: Boolean): FederationResult<Array<AccountJwk>> =
        getKeysCommand.execute(GetKeysArgs(account, includeRevoked)).toFederationResult()

    override suspend fun getAssertedKeysForAccount(
        account: Account,
        includeRevoked: Boolean,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<Array<AccountJwk>> =
        getAssertedKeysCommand.execute(GetAssertedKeysArgs(account, includeRevoked, kmsKeyRef, kid)).toFederationResult()

    override suspend fun revokeKey(account: Account, keyId: String, reason: String?): FederationResult<AccountJwk> =
        revokeKeyCommand.execute(RevokeKeyArgs(account, keyId, reason)).toFederationResult()

    override suspend fun getFederationHistoricalKeysJwt(account: Account): FederationResult<String> =
        getFederationHistoricalKeysJwtCommand.execute(GetFederationHistoricalKeysJwtArgs(account)).toFederationResult()
}

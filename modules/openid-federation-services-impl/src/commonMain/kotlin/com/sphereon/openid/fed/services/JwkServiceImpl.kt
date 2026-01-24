package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.services.command.jwk.CreateKeyCommand
import com.sphereon.openid.fed.services.command.jwk.GetAssertedKeysCommand
import com.sphereon.openid.fed.services.command.jwk.GetFederationHistoricalKeysJwtCommand
import com.sphereon.openid.fed.services.command.jwk.GetKeysCommand
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

    /**
     * Inner class implementing the Commands interface.
     * Provides access to individual commands for advanced use cases.
     */
    inner class CommandsImpl : JwkService.Commands {
        override val createKey: CreateKeyCommand
            get() = this@JwkServiceImpl.createKeyCommand

        override val getKeys: GetKeysCommand
            get() = this@JwkServiceImpl.getKeysCommand

        override val getAssertedKeys: GetAssertedKeysCommand
            get() = this@JwkServiceImpl.getAssertedKeysCommand

        override val revokeKey: RevokeKeyCommand
            get() = this@JwkServiceImpl.revokeKeyCommand

        override val getFederationHistoricalKeysJwt: GetFederationHistoricalKeysJwtCommand
            get() = this@JwkServiceImpl.getFederationHistoricalKeysJwtCommand
    }

    override val commands: JwkService.Commands = CommandsImpl()

    // Delegate all service methods to their respective commands

    override suspend fun createKey(account: Account, opts: CreateKeyArgs): FederationResult<AccountJwk> =
        createKeyCommand.createKey(account, opts)

    override suspend fun getKeys(account: Account, includeRevoked: Boolean): FederationResult<Array<AccountJwk>> =
        getKeysCommand.getKeys(account, includeRevoked)

    override suspend fun getAssertedKeysForAccount(
        account: Account,
        includeRevoked: Boolean,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<Array<AccountJwk>> =
        getAssertedKeysCommand.getAssertedKeysForAccount(account, includeRevoked, kmsKeyRef, kid)

    override suspend fun revokeKey(account: Account, keyId: String, reason: String?): FederationResult<AccountJwk> =
        revokeKeyCommand.revokeKey(account, keyId, reason)

    override suspend fun getFederationHistoricalKeysJwt(account: Account): FederationResult<String> =
        getFederationHistoricalKeysJwtCommand.getFederationHistoricalKeysJwt(account)
}

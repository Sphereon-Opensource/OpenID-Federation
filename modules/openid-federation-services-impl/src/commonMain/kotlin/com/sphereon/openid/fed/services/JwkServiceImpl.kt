package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.TenantJwk
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
import com.sphereon.openid.fed.services.command.jwk.UpdateKeyRevocationArgs
import com.sphereon.openid.fed.services.command.jwk.UpdateKeyRevocationCommand
import com.sphereon.openid.fed.services.command.jwk.RetireKeyArgs
import com.sphereon.openid.fed.services.command.jwk.RetireKeyCommand
import com.sphereon.openid.fed.services.command.jwk.ListHistoricalKeysArgs
import com.sphereon.openid.fed.services.command.jwk.ListHistoricalKeysCommand
import com.sphereon.openid.fed.services.command.jwk.SignAccountRequestJwtArgs
import com.sphereon.openid.fed.services.command.jwk.SignAccountRequestJwtCommand
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

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
@ContributesBinding(SessionScope::class, binding = binding<JwkService>())
class JwkServiceImpl(
    private val createKeyCommand: CreateKeyCommand,
    private val getKeysCommand: GetKeysCommand,
    private val getAssertedKeysCommand: GetAssertedKeysCommand,
    private val revokeKeyCommand: RevokeKeyCommand,
    private val updateKeyRevocationCommand: UpdateKeyRevocationCommand,
    private val retireKeyCommand: RetireKeyCommand,
    private val listHistoricalKeysCommand: ListHistoricalKeysCommand,
    private val signAccountRequestJwtCommand: SignAccountRequestJwtCommand,
    private val getFederationHistoricalKeysJwtCommand: GetFederationHistoricalKeysJwtCommand
) : JwkService {

    override suspend fun createKey(tenantId: String, opts: CreateKeyArgs): FederationResult<TenantJwk> =
        createKeyCommand.execute(CreateKeyCommandArgs(tenantId, opts)).toFederationResult()

    override suspend fun getKeys(tenantId: String, includeRevoked: Boolean): FederationResult<Array<TenantJwk>> =
        getKeysCommand.execute(GetKeysArgs(tenantId, includeRevoked)).toFederationResult()

    override suspend fun getAssertedKeysForAccount(
        tenantId: String,
        includeRevoked: Boolean,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<Array<TenantJwk>> =
        getAssertedKeysCommand.execute(GetAssertedKeysArgs(tenantId, includeRevoked, kmsKeyRef, kid)).toFederationResult()

    override suspend fun revokeKey(
        tenantId: String, keyId: String, reason: String?, description: String?, allowSelected: Boolean,
    ): FederationResult<TenantJwk> =
        revokeKeyCommand.execute(RevokeKeyArgs(tenantId, keyId, reason, description, allowSelected)).toFederationResult()

    override suspend fun updateKeyRevocation(
        tenantId: String, keyId: String, reason: String, revokedAt: Long?,
    ): FederationResult<TenantJwk> =
        updateKeyRevocationCommand.execute(UpdateKeyRevocationArgs(tenantId, keyId, reason, revokedAt)).toFederationResult()

    override suspend fun retireKey(tenantId: String, keyId: String): FederationResult<TenantJwk> =
        retireKeyCommand.execute(RetireKeyArgs(tenantId, keyId)).toFederationResult()

    override suspend fun listHistoricalKeys(tenantId: String): FederationResult<List<HistoricalKey>> =
        listHistoricalKeysCommand.execute(ListHistoricalKeysArgs(tenantId)).toFederationResult()

    override suspend fun signAccountRequestJwt(
        tenantId: String, typ: String, claims: JsonObject, kid: String?,
    ): FederationResult<String> =
        signAccountRequestJwtCommand.execute(SignAccountRequestJwtArgs(tenantId, typ, claims, kid)).toFederationResult()

    override suspend fun getFederationHistoricalKeysJwt(tenantId: String): FederationResult<String> =
        getFederationHistoricalKeysJwtCommand.execute(GetFederationHistoricalKeysJwtArgs(tenantId)).toFederationResult()
}

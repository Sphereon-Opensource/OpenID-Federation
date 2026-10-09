package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.TrustMarkType
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val TrustMarkOwnerKeysJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** The owner and delegation of a Trust Mark type of the account, read from the type and its owner record. */
internal fun trustMarkTypeGovernance(type: TrustMarkType): TrustMarkTypeGovernance {
    val owner = Persistence.trustMarkOwnerQueries.findByTrustMarkTypeId(type.id).executeAsOneOrNull()?.let {
        TrustMarkTypeOwner(it.owner_identifier, TrustMarkOwnerKeysJson.decodeFromString(ListSerializer(Jwk.serializer()), it.jwks))
    }
    return TrustMarkTypeGovernance(type.id, type.identifier, owner, type.delegation)
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetTrustMarkTypeGovernanceCommand>())
class GetTrustMarkTypeGovernanceCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<TrustMarkTypeRefArgs, TrustMarkTypeGovernance, FederationError>(
    commandId = GetTrustMarkTypeGovernanceCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<TrustMarkTypeRefArgs>(),
    outputTypeToken = typeToken<TrustMarkTypeGovernance>(),
), GetTrustMarkTypeGovernanceCommand {
    override suspend fun doExecute(
        args: TrustMarkTypeRefArgs,
        applyDuring: (TrustMarkTypeRefArgs) -> TrustMarkTypeRefArgs,
    ): IdkResult<TrustMarkTypeGovernance, FederationError> {
        val (tenantId, trustMarkTypeId) = applyDuring(args)
        val type = Persistence.trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return IdkResult.ok(trustMarkTypeGovernance(type))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetTrustMarkTypeOwnerCommand>())
class SetTrustMarkTypeOwnerCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<SetTrustMarkTypeOwnerArgs, TrustMarkTypeGovernance, FederationError>(
    commandId = SetTrustMarkTypeOwnerCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<SetTrustMarkTypeOwnerArgs>(),
    outputTypeToken = typeToken<TrustMarkTypeGovernance>(),
), SetTrustMarkTypeOwnerCommand {
    private val logger = execution.federationLogger("SetTrustMarkTypeOwnerCommand")

    override suspend fun doExecute(
        args: SetTrustMarkTypeOwnerArgs,
        applyDuring: (SetTrustMarkTypeOwnerArgs) -> SetTrustMarkTypeOwnerArgs,
    ): IdkResult<TrustMarkTypeGovernance, FederationError> {
        val (tenantId, trustMarkTypeId, owner, keys) = applyDuring(args)
        if (owner.isBlank()) return federationErr(InvalidRequestError("The Trust Mark owner is required"))
        if (keys.isEmpty() || keys.any { it.kid.isBlank() }) {
            return federationErr(InvalidRequestError("The Trust Mark owner needs at least one key, each with a kid"))
        }
        val type = Persistence.trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return try {
            Persistence.trustMarkOwnerQueries.transactionWithResult {
                Persistence.trustMarkOwnerQueries.deleteByTrustMarkTypeId(type.id).executeAsList()
                Persistence.trustMarkOwnerQueries.create(type.id, owner, TrustMarkOwnerKeysJson.encodeToString(ListSerializer(Jwk.serializer()), keys))
                    .executeAsOne()
            }
            IdkResult.ok(trustMarkTypeGovernance(type))
        } catch (e: Exception) {
            logger.error("Failed to set the Trust Mark owner", e)
            federationErr(ServerError("Failed to set the Trust Mark owner", e.message, e))
        }
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RemoveTrustMarkTypeOwnerCommand>())
class RemoveTrustMarkTypeOwnerCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<TrustMarkTypeRefArgs, TrustMarkTypeGovernance, FederationError>(
    commandId = RemoveTrustMarkTypeOwnerCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<TrustMarkTypeRefArgs>(),
    outputTypeToken = typeToken<TrustMarkTypeGovernance>(),
), RemoveTrustMarkTypeOwnerCommand {
    override suspend fun doExecute(
        args: TrustMarkTypeRefArgs,
        applyDuring: (TrustMarkTypeRefArgs) -> TrustMarkTypeRefArgs,
    ): IdkResult<TrustMarkTypeGovernance, FederationError> {
        val (tenantId, trustMarkTypeId) = applyDuring(args)
        val type = Persistence.trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        Persistence.trustMarkOwnerQueries.deleteByTrustMarkTypeId(type.id).executeAsList()
        return IdkResult.ok(trustMarkTypeGovernance(type))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetTrustMarkTypeDelegationCommand>())
class SetTrustMarkTypeDelegationCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<SetTrustMarkTypeDelegationArgs, TrustMarkTypeGovernance, FederationError>(
    commandId = SetTrustMarkTypeDelegationCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<SetTrustMarkTypeDelegationArgs>(),
    outputTypeToken = typeToken<TrustMarkTypeGovernance>(),
), SetTrustMarkTypeDelegationCommand {
    override suspend fun doExecute(
        args: SetTrustMarkTypeDelegationArgs,
        applyDuring: (SetTrustMarkTypeDelegationArgs) -> SetTrustMarkTypeDelegationArgs,
    ): IdkResult<TrustMarkTypeGovernance, FederationError> {
        val (tenantId, trustMarkTypeId, delegation) = applyDuring(args)
        if (delegation != null && delegation.count { it == '.' } != 2) {
            return federationErr(InvalidRequestError("A Trust Mark delegation is a signed JWT"))
        }
        val updated = Persistence.trustMarkTypeQueries.setDelegation(delegation, tenantId, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return IdkResult.ok(trustMarkTypeGovernance(updated))
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateTrustMarkDelegationCommand>())
class CreateTrustMarkDelegationCommandImpl(
    execution: SessionExecution,
    private val jwtService: JwtService,
    private val tenantContextResolver: TenantContextResolver,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
) : TypedServiceCommandAdapter<CreateTrustMarkDelegationArgs, String, FederationError>(
    commandId = CreateTrustMarkDelegationCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateTrustMarkDelegationArgs>(),
    outputTypeToken = typeToken<String>(),
), CreateTrustMarkDelegationCommand {
    override suspend fun doExecute(
        args: CreateTrustMarkDelegationArgs,
        applyDuring: (CreateTrustMarkDelegationArgs) -> CreateTrustMarkDelegationArgs,
    ): IdkResult<String, FederationError> {
        val input = applyDuring(args)
        if (input.trustMarkType.isBlank() || input.subject.isBlank()) {
            return federationErr(InvalidRequestError("A delegation names its Trust Mark type and the issuer it delegates to"))
        }
        val expiresAt = input.expiresAt
        if (expiresAt != null && expiresAt <= input.issuedAt) {
            return federationErr(InvalidRequestError("A delegation must expire after it is issued"))
        }
        val owner = tenantContextResolver.resolveIdentifier(input.tenantId)
            ?: return federationErr(TenantNotFoundError(input.tenantId))
        val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(input.tenantId, owner))
        if (resolved.isErr) return resolved.error.asErrorResult()
        val key = resolved.value
        val payload: JsonObject = buildJsonObject {
            put("iss", owner)
            put("sub", input.subject)
            put("trust_mark_type", input.trustMarkType)
            put("iat", input.issuedAt)
            expiresAt?.let { put("exp", it) }
            input.ref?.let { put("ref", it) }
        }
        val header = JwtHeader(typ = "trust-mark-delegation+jwt", kid = key.kid, alg = key.alg)
        val signed = jwtService.signPayload(payload, header, key.kid, key.kmsKeyRef, key.kms)
        return if (signed.isErr) signed.error.asErrorResult() else IdkResult.ok(signed.value)
    }
}

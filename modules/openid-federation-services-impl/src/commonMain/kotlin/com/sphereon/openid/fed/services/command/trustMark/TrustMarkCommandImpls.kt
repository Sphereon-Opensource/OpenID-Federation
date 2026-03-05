package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustMarkNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult

import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.signPayload
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

// GetTrustMarksForAccountCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarksForAccountCommand::class)
class GetTrustMarksForAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetTrustMarksForAccountArgs, List<TrustMark>>(
    commandId = GetTrustMarksForAccountCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetTrustMarksForAccountArgs>(),
    outputTypeToken = typeToken<List<TrustMark>>()
), GetTrustMarksForAccountCommand {
    private val logger = execution.federationLogger("GetTrustMarksForAccountCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: GetTrustMarksForAccountArgs, applyDuring: (GetTrustMarksForAccountArgs) -> GetTrustMarksForAccountArgs): IdkResult<List<TrustMark>, IdkError> {
        val (tenantId) = applyDuring(args)
        return try {
            IdkResult.ok(trustMarkQueries.findByAccountId(tenantId).executeAsList().map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to get trust marks", e)
            federationErr(ServerError("Failed to get trust marks", e.message, e))
        }
    }
}

// CreateTrustMarkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateTrustMarkCommand::class)
class CreateTrustMarkCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val jwtService: JwtService,
    private val tenantContextResolver: TenantContextResolver
) : TypedServiceCommandAdapter<CreateTrustMarkArgs, CreateTrustMarkResult>(
    commandId = CreateTrustMarkCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateTrustMarkArgs>(),
    outputTypeToken = typeToken<CreateTrustMarkResult>()
), CreateTrustMarkCommand {
    private val logger = execution.federationLogger("CreateTrustMarkCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: CreateTrustMarkArgs, applyDuring: (CreateTrustMarkArgs) -> CreateTrustMarkArgs): IdkResult<CreateTrustMarkResult, IdkError> {
        val (tenantId, request, currentTimeMillis) = applyDuring(args)
        val keysResult = jwkService.getKeys(tenantId, includeRevoked = false).toIdkErrorResult()
        if (keysResult.isErr) return keysResult.error.asErrorResult()
        val keys = keysResult.value
        if (keys.isEmpty()) return federationErr(KeyNotFoundError("account:$tenantId"))

        val key = keys[0]
        val iat = request.iat ?: (currentTimeMillis / 1000).toDouble()

        return try {
            val accountIdentifier = tenantContextResolver.resolveIdentifier(tenantId)
                ?: return federationErr(TenantNotFoundError(tenantId))

            val trustMark = TrustMarkObjectBuilder()
                .iss(accountIdentifier)
                .sub(request.sub)
                .trustMarkType(request.trustMarkType)
                .iat(iat)
                .logoUri(request.logoUri)
                .ref(request.ref)
                .delegation(request.delegation)
                .trustMarkLifetime(request.trustMarkLifetime)
            if (request.exp != null) trustMark.exp(request.exp)

            val header = JwtHeader(typ = "trust-mark+jwt", kid = key.kid, alg = key.alg ?: "RS256")
            val jwtResult = jwtService.signPayload(trustMark.build(), header, key.kid, key.kmsKeyRef, key.kms).toIdkErrorResult()
            if (jwtResult.isErr) return jwtResult.error.asErrorResult()
            val jwt = jwtResult.value

            if (request.dryRun == true) {
                return IdkResult.ok(CreateTrustMarkResult(trustMarkValue = jwt, trustMarkType = request.trustMarkType, sub = request.sub, accountId = tenantId, iat = iat))
            }

            val entity = trustMarkQueries.create(tenantId, request.trustMarkType, request.sub, jwt, iat.toInt(), request.exp?.toInt()).executeAsOne()
            IdkResult.ok(CreateTrustMarkResult(accountId = entity.account_id, trustMarkValue = entity.trust_mark_value, id = entity.id, iat = entity.iat.toDouble(), sub = entity.sub, trustMarkType = entity.trust_mark_id, exp = entity.exp?.toDouble()))
        } catch (e: Exception) {
            logger.error("Failed to create trust mark", e)
            federationErr(ServerError("Failed to create trust mark", e.message, e))
        }
    }
}

// DeleteTrustMarkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkCommand::class)
class DeleteTrustMarkCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteTrustMarkArgs, TrustMarkEntity>(
    commandId = DeleteTrustMarkCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteTrustMarkArgs>(),
    outputTypeToken = typeToken<TrustMarkEntity>()
), DeleteTrustMarkCommand {
    private val logger = execution.federationLogger("DeleteTrustMarkCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: DeleteTrustMarkArgs, applyDuring: (DeleteTrustMarkArgs) -> DeleteTrustMarkArgs): IdkResult<TrustMarkEntity, IdkError> {
        val (tenantId, trustMarkId) = applyDuring(args)
        trustMarkQueries.findByAccountIdAndId(tenantId, trustMarkId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkNotFoundError(trustMarkId))
        return try {
            IdkResult.ok(trustMarkQueries.delete(trustMarkId).executeAsOne())
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark", e)
            federationErr(ServerError("Failed to delete trust mark", e.message, e))
        }
    }
}

// GetTrustMarkStatusCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkStatusCommand::class)
class GetTrustMarkStatusCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetTrustMarkStatusArgs, Boolean>(
    commandId = GetTrustMarkStatusCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetTrustMarkStatusArgs>(),
    outputTypeToken = typeToken<Boolean>()
), GetTrustMarkStatusCommand {
    private val logger = execution.federationLogger("GetTrustMarkStatusCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: GetTrustMarkStatusArgs, applyDuring: (GetTrustMarkStatusArgs) -> GetTrustMarkStatusArgs): IdkResult<Boolean, IdkError> {
        val (tenantId, statusRequest) = applyDuring(args)
        return try {
            val trustMarks = trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(tenantId, statusRequest.trustMarkType, statusRequest.sub).executeAsList()
            if (statusRequest.iat != null) {
                IdkResult.ok(trustMarks.any { it.iat.toDouble() == statusRequest.iat })
            } else {
                IdkResult.ok(trustMarks.isNotEmpty())
            }
        } catch (e: Exception) {
            logger.error("Failed to check trust mark status", e)
            federationErr(ServerError("Failed to check trust mark status", e.message, e))
        }
    }
}

// GetTrustMarkedSubsCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkedSubsCommand::class)
class GetTrustMarkedSubsCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetTrustMarkedSubsArgs, Array<String>>(
    commandId = GetTrustMarkedSubsCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetTrustMarkedSubsArgs>(),
    outputTypeToken = typeToken<Array<String>>()
), GetTrustMarkedSubsCommand {
    private val logger = execution.federationLogger("GetTrustMarkedSubsCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: GetTrustMarkedSubsArgs, applyDuring: (GetTrustMarkedSubsArgs) -> GetTrustMarkedSubsArgs): IdkResult<Array<String>, IdkError> {
        val (tenantId, listRequest) = applyDuring(args)
        return try {
            val subs = if (listRequest.sub != null) {
                trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(tenantId, listRequest.trustMarkType, listRequest.sub!!).executeAsList()
            } else {
                trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(tenantId, listRequest.trustMarkType).executeAsList()
            }
            IdkResult.ok(subs.toTypedArray())
        } catch (e: Exception) {
            logger.error("Failed to get trust marked subjects", e)
            federationErr(ServerError("Failed to get trust marked subjects", e.message, e))
        }
    }
}

// GetTrustMarkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkCommand::class)
class GetTrustMarkCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetTrustMarkArgs, String>(
    commandId = GetTrustMarkCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetTrustMarkArgs>(),
    outputTypeToken = typeToken<String>()
), GetTrustMarkCommand {
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun doExecute(args: GetTrustMarkArgs, applyDuring: (GetTrustMarkArgs) -> GetTrustMarkArgs): IdkResult<String, IdkError> {
        val (tenantId, request) = applyDuring(args)
        val trustMark = trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(tenantId, request.trustMarkType, request.sub).executeAsOneOrNull()
            ?: return federationErr(TrustMarkNotFoundError("${request.trustMarkType}:${request.sub}"))
        return IdkResult.ok(trustMark.trust_mark_value)
    }
}

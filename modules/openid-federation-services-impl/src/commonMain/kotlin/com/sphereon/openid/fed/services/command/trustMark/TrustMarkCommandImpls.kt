package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.TrustMarkObjectBuilder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustMarkNotFoundError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
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
) : ExecutionScopedCommandAdapter<GetTrustMarksForAccountArgs, List<TrustMark>, FederationError>(
    id = GetTrustMarksForAccountCommand.COMMAND_ID, execution = execution
), GetTrustMarksForAccountCommand {
    private val logger = Log.app().withTag("GetTrustMarksForAccountCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun getTrustMarksForAccount(account: Account): IdkResult<List<TrustMark>, FederationError> =
        execute(GetTrustMarksForAccountArgs(account))

    override suspend fun doExecute(args: GetTrustMarksForAccountArgs, applyDuring: (GetTrustMarksForAccountArgs) -> GetTrustMarksForAccountArgs): IdkResult<List<TrustMark>, FederationError> {
        val (account) = applyDuring(args)
        return try {
            IdkResult.ok(trustMarkQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to get trust marks", e)
            IdkResult.err(ServerError("Failed to get trust marks", e.message, e))
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
    private val accountService: AccountService
) : ExecutionScopedCommandAdapter<CreateTrustMarkArgs, CreateTrustMarkResult, FederationError>(
    id = CreateTrustMarkCommand.COMMAND_ID, execution = execution
), CreateTrustMarkCommand {
    private val logger = Log.app().withTag("CreateTrustMarkCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun createTrustMark(account: Account, body: CreateTrustMarkRequest, currentTimeMillis: Long): IdkResult<CreateTrustMarkResult, FederationError> =
        execute(CreateTrustMarkArgs(account, body, currentTimeMillis))

    override suspend fun doExecute(args: CreateTrustMarkArgs, applyDuring: (CreateTrustMarkArgs) -> CreateTrustMarkArgs): IdkResult<CreateTrustMarkResult, FederationError> {
        val (account, request, currentTimeMillis) = applyDuring(args)
        val keysResult = jwkService.getKeys(account, includeRevoked = false)
        if (keysResult.isErr) return keysResult.error.asErrorResult()
        val keys = keysResult.value
        if (keys.isEmpty()) return IdkResult.err(KeyNotFoundError("account:${account.id}"))

        val key = keys[0]
        val iat = request.iat ?: (currentTimeMillis / 1000).toInt()

        return try {
            val accountIdentifierResult = accountService.getAccountIdentifierByAccount(account)
            if (accountIdentifierResult.isErr) return accountIdentifierResult.error.asErrorResult()
            val accountIdentifier = accountIdentifierResult.value

            val trustMark = TrustMarkObjectBuilder()
                .iss(accountIdentifier)
                .sub(request.sub)
                .id(request.trustMarkId)
                .iat(iat)
                .logoUri(request.logoUri)
                .ref(request.ref)
                .delegation(request.delegation)
            if (request.exp != null) trustMark.exp(request.exp)

            val header = JwtHeader(typ = "trust-mark+jwt", kid = key.kid, alg = key.alg ?: "RS256")
            val jwtResult = jwtService.signPayload(trustMark.build(), header, key.kid, key.kmsKeyRef, key.kms)
            if (jwtResult.isErr) return jwtResult.error.asErrorResult()
            val jwt = jwtResult.value

            if (request.dryRun == true) {
                return IdkResult.ok(CreateTrustMarkResult(trustMarkValue = jwt, trustMarkId = request.trustMarkId, sub = request.sub, accountId = account.id, iat = iat))
            }

            val entity = trustMarkQueries.create(account.id, request.trustMarkId, request.sub, jwt, iat, request.exp).executeAsOne()
            IdkResult.ok(CreateTrustMarkResult(accountId = entity.account_id, trustMarkValue = entity.trust_mark_value, id = entity.id, iat = entity.iat, sub = entity.sub, trustMarkId = entity.trust_mark_id, exp = entity.exp))
        } catch (e: Exception) {
            logger.error("Failed to create trust mark", e)
            IdkResult.err(ServerError("Failed to create trust mark", e.message, e))
        }
    }
}

// DeleteTrustMarkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkCommand::class)
class DeleteTrustMarkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteTrustMarkArgs, TrustMarkEntity, FederationError>(
    id = DeleteTrustMarkCommand.COMMAND_ID, execution = execution
), DeleteTrustMarkCommand {
    private val logger = Log.app().withTag("DeleteTrustMarkCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun deleteTrustMark(account: Account, id: String): IdkResult<TrustMarkEntity, FederationError> =
        execute(DeleteTrustMarkArgs(account, id))

    override suspend fun doExecute(args: DeleteTrustMarkArgs, applyDuring: (DeleteTrustMarkArgs) -> DeleteTrustMarkArgs): IdkResult<TrustMarkEntity, FederationError> {
        val (account, trustMarkId) = applyDuring(args)
        trustMarkQueries.findByAccountIdAndId(account.id, trustMarkId).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkNotFoundError(trustMarkId))
        return try {
            IdkResult.ok(trustMarkQueries.delete(trustMarkId).executeAsOne())
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark", e)
            IdkResult.err(ServerError("Failed to delete trust mark", e.message, e))
        }
    }
}

// GetTrustMarkStatusCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkStatusCommand::class)
class GetTrustMarkStatusCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetTrustMarkStatusArgs, Boolean, FederationError>(
    id = GetTrustMarkStatusCommand.COMMAND_ID, execution = execution
), GetTrustMarkStatusCommand {
    private val logger = Log.app().withTag("GetTrustMarkStatusCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): IdkResult<Boolean, FederationError> =
        execute(GetTrustMarkStatusArgs(account, request))

    override suspend fun doExecute(args: GetTrustMarkStatusArgs, applyDuring: (GetTrustMarkStatusArgs) -> GetTrustMarkStatusArgs): IdkResult<Boolean, FederationError> {
        val (account, statusRequest) = applyDuring(args)
        return try {
            val trustMarks = trustMarkQueries.findByAccountIdAndAndSubAndTrustMarkTypeIdentifier(account.id, statusRequest.trustMarkId, statusRequest.sub).executeAsList()
            if (statusRequest.iat != null) {
                IdkResult.ok(trustMarks.any { it.iat == statusRequest.iat })
            } else {
                IdkResult.ok(trustMarks.isNotEmpty())
            }
        } catch (e: Exception) {
            logger.error("Failed to check trust mark status", e)
            IdkResult.err(ServerError("Failed to check trust mark status", e.message, e))
        }
    }
}

// GetTrustMarkedSubsCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkedSubsCommand::class)
class GetTrustMarkedSubsCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetTrustMarkedSubsArgs, Array<String>, FederationError>(
    id = GetTrustMarkedSubsCommand.COMMAND_ID, execution = execution
), GetTrustMarkedSubsCommand {
    private val logger = Log.app().withTag("GetTrustMarkedSubsCommand")
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): IdkResult<Array<String>, FederationError> =
        execute(GetTrustMarkedSubsArgs(account, request))

    override suspend fun doExecute(args: GetTrustMarkedSubsArgs, applyDuring: (GetTrustMarkedSubsArgs) -> GetTrustMarkedSubsArgs): IdkResult<Array<String>, FederationError> {
        val (account, listRequest) = applyDuring(args)
        return try {
            val subs = if (listRequest.sub != null) {
                trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifierAndSub(account.id, listRequest.trustMarkId, listRequest.sub!!).executeAsList()
            } else {
                trustMarkQueries.findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(account.id, listRequest.trustMarkId).executeAsList()
            }
            IdkResult.ok(subs.toTypedArray())
        } catch (e: Exception) {
            logger.error("Failed to get trust marked subjects", e)
            IdkResult.err(ServerError("Failed to get trust marked subjects", e.message, e))
        }
    }
}

// GetTrustMarkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetTrustMarkCommand::class)
class GetTrustMarkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetTrustMarkArgs, String, FederationError>(
    id = GetTrustMarkCommand.COMMAND_ID, execution = execution
), GetTrustMarkCommand {
    private val trustMarkQueries = Persistence.trustMarkQueries

    override suspend fun getTrustMark(account: Account, request: TrustMarkRequest): IdkResult<String, FederationError> =
        execute(GetTrustMarkArgs(account, request))

    override suspend fun doExecute(args: GetTrustMarkArgs, applyDuring: (GetTrustMarkArgs) -> GetTrustMarkArgs): IdkResult<String, FederationError> {
        val (account, request) = applyDuring(args)
        val trustMark = trustMarkQueries.getLatestByAccountIdAndTrustMarkTypeIdentifierAndSub(account.id, request.trustMarkId, request.sub).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkNotFoundError("${request.trustMarkId}:${request.sub}"))
        return IdkResult.ok(trustMark.trust_mark_value)
    }
}

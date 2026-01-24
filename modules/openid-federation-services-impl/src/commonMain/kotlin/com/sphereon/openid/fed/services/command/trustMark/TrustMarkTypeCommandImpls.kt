package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// CreateTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateTrustMarkTypeCommand::class)
class CreateTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateTrustMarkTypeArgs, TrustMarkType, FederationError>(
    id = CreateTrustMarkTypeCommand.COMMAND_ID, execution = execution
), CreateTrustMarkTypeCommand {
    private val logger = Log.app().withTag("CreateTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): IdkResult<TrustMarkType, FederationError> =
        execute(CreateTrustMarkTypeArgs(account, createDto), execution.sessionContext)

    override suspend fun doExecute(args: CreateTrustMarkTypeArgs, sessionContext: SessionContext, applyDuring: (CreateTrustMarkTypeArgs) -> CreateTrustMarkTypeArgs): IdkResult<TrustMarkType, FederationError> {
        val (account, createDto) = applyDuring(args)
        logger.info("Creating trust mark type ${createDto.identifier} for username: ${account.username}")

        val existing = trustMarkTypeQueries.findByAccountIdAndIdentifier(account.id, createDto.identifier).executeAsOneOrNull()
        if (existing != null) {
            return IdkResult.err(InvalidRequestError("A trust mark type with the given identifier already exists for this account."))
        }

        return try {
            val created = trustMarkTypeQueries.create(account.id, createDto.identifier).executeAsOne()
            logger.info("Successfully created trust mark type with ID: ${created.id}")
            IdkResult.ok(created.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create trust mark type", e)
            IdkResult.err(ServerError("Failed to create trust mark type", e.message, e))
        }
    }
}

// FindAllTrustMarkTypesByAccountCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindAllTrustMarkTypesByAccountCommand::class)
class FindAllTrustMarkTypesByAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindAllTrustMarkTypesByAccountArgs, List<TrustMarkType>, FederationError>(
    id = FindAllTrustMarkTypesByAccountCommand.COMMAND_ID, execution = execution
), FindAllTrustMarkTypesByAccountCommand {
    private val logger = Log.app().withTag("FindAllTrustMarkTypesByAccountCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun findAllByAccount(account: Account): IdkResult<List<TrustMarkType>, FederationError> =
        execute(FindAllTrustMarkTypesByAccountArgs(account), execution.sessionContext)

    override suspend fun doExecute(args: FindAllTrustMarkTypesByAccountArgs, sessionContext: SessionContext, applyDuring: (FindAllTrustMarkTypesByAccountArgs) -> FindAllTrustMarkTypesByAccountArgs): IdkResult<List<TrustMarkType>, FederationError> {
        val (account) = applyDuring(args)
        return try {
            IdkResult.ok(trustMarkTypeQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find trust mark types", e)
            IdkResult.err(ServerError("Failed to find trust mark types", e.message, e))
        }
    }
}

// FindTrustMarkTypeByIdCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindTrustMarkTypeByIdCommand::class)
class FindTrustMarkTypeByIdCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindTrustMarkTypeByIdArgs, TrustMarkType, FederationError>(
    id = FindTrustMarkTypeByIdCommand.COMMAND_ID, execution = execution
), FindTrustMarkTypeByIdCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun findById(account: Account, id: String): IdkResult<TrustMarkType, FederationError> =
        execute(FindTrustMarkTypeByIdArgs(account, id), execution.sessionContext)

    override suspend fun doExecute(args: FindTrustMarkTypeByIdArgs, sessionContext: SessionContext, applyDuring: (FindTrustMarkTypeByIdArgs) -> FindTrustMarkTypeByIdArgs): IdkResult<TrustMarkType, FederationError> {
        val (account, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
        return if (type == null) IdkResult.err(TrustMarkTypeNotFoundError(id)) else IdkResult.ok(type.toDTO())
    }
}

// DeleteTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkTypeCommand::class)
class DeleteTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteTrustMarkTypeArgs, TrustMarkType, FederationError>(
    id = DeleteTrustMarkTypeCommand.COMMAND_ID, execution = execution
), DeleteTrustMarkTypeCommand {
    private val logger = Log.app().withTag("DeleteTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun deleteTrustMarkType(account: Account, id: String): IdkResult<TrustMarkType, FederationError> =
        execute(DeleteTrustMarkTypeArgs(account, id), execution.sessionContext)

    override suspend fun doExecute(args: DeleteTrustMarkTypeArgs, sessionContext: SessionContext, applyDuring: (DeleteTrustMarkTypeArgs) -> DeleteTrustMarkTypeArgs): IdkResult<TrustMarkType, FederationError> {
        val (account, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkTypeNotFoundError(id))
        return try {
            val deleted = trustMarkTypeQueries.delete(id).executeAsOne()
            IdkResult.ok(deleted.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark type", e)
            IdkResult.err(ServerError("Failed to delete trust mark type", e.message, e))
        }
    }
}

// GetIssuersForTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetIssuersForTrustMarkTypeCommand::class)
class GetIssuersForTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetIssuersForTrustMarkTypeArgs, Array<TrustMarkIssuer>, FederationError>(
    id = GetIssuersForTrustMarkTypeCommand.COMMAND_ID, execution = execution
), GetIssuersForTrustMarkTypeCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): IdkResult<Array<TrustMarkIssuer>, FederationError> =
        execute(GetIssuersForTrustMarkTypeArgs(account, trustMarkTypeId), execution.sessionContext)

    override suspend fun doExecute(args: GetIssuersForTrustMarkTypeArgs, sessionContext: SessionContext, applyDuring: (GetIssuersForTrustMarkTypeArgs) -> GetIssuersForTrustMarkTypeArgs): IdkResult<Array<TrustMarkIssuer>, FederationError> {
        val (account, trustMarkTypeId) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return IdkResult.ok(trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().toTypedArray())
    }
}

// AddIssuerToTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = AddIssuerToTrustMarkTypeCommand::class)
class AddIssuerToTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<AddIssuerToTrustMarkTypeArgs, TrustMarkIssuer, FederationError>(
    id = AddIssuerToTrustMarkTypeCommand.COMMAND_ID, execution = execution
), AddIssuerToTrustMarkTypeCommand {
    private val logger = Log.app().withTag("AddIssuerToTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): IdkResult<TrustMarkIssuer, FederationError> =
        execute(AddIssuerToTrustMarkTypeArgs(account, trustMarkTypeId, issuerIdentifier), execution.sessionContext)

    override suspend fun doExecute(args: AddIssuerToTrustMarkTypeArgs, sessionContext: SessionContext, applyDuring: (AddIssuerToTrustMarkTypeArgs) -> AddIssuerToTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, FederationError> {
        val (account, trustMarkTypeId, issuerIdentifier) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkTypeNotFoundError(trustMarkTypeId))

        val existing = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().any { it.issuer_identifier == issuerIdentifier }
        if (existing) return IdkResult.err(InvalidRequestError("Issuer $issuerIdentifier is already associated with the trust mark definition."))

        return try {
            val created = trustMarkIssuerQueries.create(trustMarkTypeId, issuerIdentifier).executeAsOne()
            IdkResult.ok(created)
        } catch (e: Exception) {
            logger.error("Failed to add issuer", e)
            IdkResult.err(ServerError("Failed to add issuer", e.message, e))
        }
    }
}

// RemoveIssuerFromTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = RemoveIssuerFromTrustMarkTypeCommand::class)
class RemoveIssuerFromTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<RemoveIssuerFromTrustMarkTypeArgs, TrustMarkIssuer, FederationError>(
    id = RemoveIssuerFromTrustMarkTypeCommand.COMMAND_ID, execution = execution
), RemoveIssuerFromTrustMarkTypeCommand {
    private val logger = Log.app().withTag("RemoveIssuerFromTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): IdkResult<TrustMarkIssuer, FederationError> =
        execute(RemoveIssuerFromTrustMarkTypeArgs(account, trustMarkTypeId, issuerId), execution.sessionContext)

    override suspend fun doExecute(args: RemoveIssuerFromTrustMarkTypeArgs, sessionContext: SessionContext, applyDuring: (RemoveIssuerFromTrustMarkTypeArgs) -> RemoveIssuerFromTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, FederationError> {
        val (account, trustMarkTypeId, issuerId) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return IdkResult.err(TrustMarkTypeNotFoundError(trustMarkTypeId))

        val issuer = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().find { it.id == issuerId }
            ?: return IdkResult.err(InvalidRequestError("Issuer $issuerId is not associated with the trust mark type."))

        return try {
            val removed = trustMarkIssuerQueries.delete(trustMarkTypeId, issuerId).executeAsOne()
            IdkResult.ok(removed)
        } catch (e: Exception) {
            logger.error("Failed to remove issuer", e)
            IdkResult.err(ServerError("Failed to remove issuer", e.message, e))
        }
    }
}

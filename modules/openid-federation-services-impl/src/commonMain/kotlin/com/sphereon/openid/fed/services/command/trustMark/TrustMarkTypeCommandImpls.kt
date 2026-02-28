package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<CreateTrustMarkTypeArgs, TrustMarkType>(
    commandId = CreateTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), CreateTrustMarkTypeCommand {
    private val logger = Log.app().withTag("CreateTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: CreateTrustMarkTypeArgs, applyDuring: (CreateTrustMarkTypeArgs) -> CreateTrustMarkTypeArgs): IdkResult<TrustMarkType, IdkError> {
        val (account, createDto) = applyDuring(args)
        logger.info("Creating trust mark type ${createDto.identifier} for username: ${account.username}")

        val existing = trustMarkTypeQueries.findByAccountIdAndIdentifier(account.id, createDto.identifier).executeAsOneOrNull()
        if (existing != null) {
            return federationErr(InvalidRequestError("A trust mark type with the given identifier already exists for this account."))
        }

        return try {
            val created = trustMarkTypeQueries.create(createDto.identifier, account.id).executeAsOne()
            logger.info("Successfully created trust mark type with ID: ${created.id}")
            IdkResult.ok(created.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create trust mark type", e)
            federationErr(ServerError("Failed to create trust mark type", e.message, e))
        }
    }
}

// FindAllTrustMarkTypesByAccountCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindAllTrustMarkTypesByAccountCommand::class)
class FindAllTrustMarkTypesByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindAllTrustMarkTypesByAccountArgs, List<TrustMarkType>>(
    commandId = FindAllTrustMarkTypesByAccountCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindAllTrustMarkTypesByAccountArgs>(),
    outputTypeToken = typeToken<List<TrustMarkType>>()
), FindAllTrustMarkTypesByAccountCommand {
    private val logger = Log.app().withTag("FindAllTrustMarkTypesByAccountCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: FindAllTrustMarkTypesByAccountArgs, applyDuring: (FindAllTrustMarkTypesByAccountArgs) -> FindAllTrustMarkTypesByAccountArgs): IdkResult<List<TrustMarkType>, IdkError> {
        val (account) = applyDuring(args)
        return try {
            IdkResult.ok(trustMarkTypeQueries.findByAccountId(account.id).executeAsList().map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find trust mark types", e)
            federationErr(ServerError("Failed to find trust mark types", e.message, e))
        }
    }
}

// FindTrustMarkTypeByIdCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindTrustMarkTypeByIdCommand::class)
class FindTrustMarkTypeByIdCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindTrustMarkTypeByIdArgs, TrustMarkType>(
    commandId = FindTrustMarkTypeByIdCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindTrustMarkTypeByIdArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), FindTrustMarkTypeByIdCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: FindTrustMarkTypeByIdArgs, applyDuring: (FindTrustMarkTypeByIdArgs) -> FindTrustMarkTypeByIdArgs): IdkResult<TrustMarkType, IdkError> {
        val (account, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
        return if (type == null) federationErr(TrustMarkTypeNotFoundError(id)) else IdkResult.ok(type.toDTO())
    }
}

// DeleteTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteTrustMarkTypeCommand::class)
class DeleteTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteTrustMarkTypeArgs, TrustMarkType>(
    commandId = DeleteTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), DeleteTrustMarkTypeCommand {
    private val logger = Log.app().withTag("DeleteTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: DeleteTrustMarkTypeArgs, applyDuring: (DeleteTrustMarkTypeArgs) -> DeleteTrustMarkTypeArgs): IdkResult<TrustMarkType, IdkError> {
        val (account, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(id))
        return try {
            val deleted = trustMarkTypeQueries.delete(id).executeAsOne()
            IdkResult.ok(deleted.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete trust mark type", e)
            federationErr(ServerError("Failed to delete trust mark type", e.message, e))
        }
    }
}

// GetIssuersForTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetIssuersForTrustMarkTypeCommand::class)
class GetIssuersForTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetIssuersForTrustMarkTypeArgs, Array<TrustMarkIssuer>>(
    commandId = GetIssuersForTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetIssuersForTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<Array<TrustMarkIssuer>>()
), GetIssuersForTrustMarkTypeCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: GetIssuersForTrustMarkTypeArgs, applyDuring: (GetIssuersForTrustMarkTypeArgs) -> GetIssuersForTrustMarkTypeArgs): IdkResult<Array<TrustMarkIssuer>, IdkError> {
        val (account, trustMarkTypeId) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return IdkResult.ok(trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().toTypedArray())
    }
}

// AddIssuerToTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = AddIssuerToTrustMarkTypeCommand::class)
class AddIssuerToTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<AddIssuerToTrustMarkTypeArgs, TrustMarkIssuer>(
    commandId = AddIssuerToTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<AddIssuerToTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkIssuer>()
), AddIssuerToTrustMarkTypeCommand {
    private val logger = Log.app().withTag("AddIssuerToTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: AddIssuerToTrustMarkTypeArgs, applyDuring: (AddIssuerToTrustMarkTypeArgs) -> AddIssuerToTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, IdkError> {
        val (account, trustMarkTypeId, issuerIdentifier) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))

        val existing = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().any { it.issuer_identifier == issuerIdentifier }
        if (existing) return federationErr(InvalidRequestError("Issuer $issuerIdentifier is already associated with the trust mark definition."))

        return try {
            val created = trustMarkIssuerQueries.create(trustMarkTypeId, issuerIdentifier).executeAsOne()
            IdkResult.ok(created)
        } catch (e: Exception) {
            logger.error("Failed to add issuer", e)
            federationErr(ServerError("Failed to add issuer", e.message, e))
        }
    }
}

// RemoveIssuerFromTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = RemoveIssuerFromTrustMarkTypeCommand::class)
class RemoveIssuerFromTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<RemoveIssuerFromTrustMarkTypeArgs, TrustMarkIssuer>(
    commandId = RemoveIssuerFromTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<RemoveIssuerFromTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkIssuer>()
), RemoveIssuerFromTrustMarkTypeCommand {
    private val logger = Log.app().withTag("RemoveIssuerFromTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: RemoveIssuerFromTrustMarkTypeArgs, applyDuring: (RemoveIssuerFromTrustMarkTypeArgs) -> RemoveIssuerFromTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, IdkError> {
        val (account, trustMarkTypeId, issuerId) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(account.id, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))

        val issuer = trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().find { it.id == issuerId }
            ?: return federationErr(InvalidRequestError("Issuer $issuerId is not associated with the trust mark type."))

        return try {
            val removed = trustMarkIssuerQueries.delete(trustMarkTypeId, issuerId).executeAsOne()
            IdkResult.ok(removed)
        } catch (e: Exception) {
            logger.error("Failed to remove issuer", e)
            federationErr(ServerError("Failed to remove issuer", e.message, e))
        }
    }
}

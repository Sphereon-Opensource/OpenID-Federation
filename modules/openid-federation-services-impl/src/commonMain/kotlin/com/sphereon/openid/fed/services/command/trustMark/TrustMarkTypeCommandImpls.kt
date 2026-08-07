package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
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
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// CreateTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateTrustMarkTypeCommand>())
class CreateTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateTrustMarkTypeArgs, TrustMarkType, FederationError>(
    commandId = CreateTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), CreateTrustMarkTypeCommand {
    private val logger = execution.federationLogger("CreateTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: CreateTrustMarkTypeArgs, applyDuring: (CreateTrustMarkTypeArgs) -> CreateTrustMarkTypeArgs): IdkResult<TrustMarkType, FederationError> {
        val (tenantId, createDto) = applyDuring(args)
        logger.info("Creating trust mark type ${createDto.identifier} for username: ${tenantId}")

        val existing = trustMarkTypeQueries.findByAccountIdAndIdentifier(tenantId, createDto.identifier).executeAsOneOrNull()
        if (existing != null) {
            return federationErr(InvalidRequestError("A trust mark type with the given identifier already exists for this account."))
        }

        return try {
            val created = trustMarkTypeQueries.create(createDto.identifier, tenantId).executeAsOne()
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
@ContributesBinding(SessionScope::class, binding = binding<FindAllTrustMarkTypesByAccountCommand>())
class FindAllTrustMarkTypesByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindAllTrustMarkTypesByAccountArgs, List<TrustMarkType>, FederationError>(
    commandId = FindAllTrustMarkTypesByAccountCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindAllTrustMarkTypesByAccountArgs>(),
    outputTypeToken = typeToken<List<TrustMarkType>>()
), FindAllTrustMarkTypesByAccountCommand {
    private val logger = execution.federationLogger("FindAllTrustMarkTypesByAccountCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: FindAllTrustMarkTypesByAccountArgs, applyDuring: (FindAllTrustMarkTypesByAccountArgs) -> FindAllTrustMarkTypesByAccountArgs): IdkResult<List<TrustMarkType>, FederationError> {
        val (tenantId) = applyDuring(args)
        return try {
            IdkResult.ok(trustMarkTypeQueries.findByAccountId(tenantId).executeAsList().map { it.toDTO() })
        } catch (e: Exception) {
            logger.error("Failed to find trust mark types", e)
            federationErr(ServerError("Failed to find trust mark types", e.message, e))
        }
    }
}

// FindTrustMarkTypeByIdCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindTrustMarkTypeByIdCommand>())
class FindTrustMarkTypeByIdCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindTrustMarkTypeByIdArgs, TrustMarkType, FederationError>(
    commandId = FindTrustMarkTypeByIdCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindTrustMarkTypeByIdArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), FindTrustMarkTypeByIdCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: FindTrustMarkTypeByIdArgs, applyDuring: (FindTrustMarkTypeByIdArgs) -> FindTrustMarkTypeByIdArgs): IdkResult<TrustMarkType, FederationError> {
        val (tenantId, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(tenantId, id).executeAsOneOrNull()
        return if (type == null) federationErr(TrustMarkTypeNotFoundError(id)) else IdkResult.ok(type.toDTO())
    }
}

// DeleteTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteTrustMarkTypeCommand>())
class DeleteTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteTrustMarkTypeArgs, TrustMarkType, FederationError>(
    commandId = DeleteTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkType>()
), DeleteTrustMarkTypeCommand {
    private val logger = execution.federationLogger("DeleteTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries

    override suspend fun doExecute(args: DeleteTrustMarkTypeArgs, applyDuring: (DeleteTrustMarkTypeArgs) -> DeleteTrustMarkTypeArgs): IdkResult<TrustMarkType, FederationError> {
        val (tenantId, id) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(tenantId, id).executeAsOneOrNull()
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
@ContributesBinding(SessionScope::class, binding = binding<GetIssuersForTrustMarkTypeCommand>())
class GetIssuersForTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetIssuersForTrustMarkTypeArgs, Array<TrustMarkIssuer>, FederationError>(
    commandId = GetIssuersForTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetIssuersForTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<Array<TrustMarkIssuer>>()
), GetIssuersForTrustMarkTypeCommand {
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: GetIssuersForTrustMarkTypeArgs, applyDuring: (GetIssuersForTrustMarkTypeArgs) -> GetIssuersForTrustMarkTypeArgs): IdkResult<Array<TrustMarkIssuer>, FederationError> {
        val (tenantId, trustMarkTypeId) = applyDuring(args)
        val type = trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
            ?: return federationErr(TrustMarkTypeNotFoundError(trustMarkTypeId))
        return IdkResult.ok(trustMarkIssuerQueries.findByTrustMarkTypeId(trustMarkTypeId).executeAsList().toTypedArray())
    }
}

// AddIssuerToTrustMarkTypeCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AddIssuerToTrustMarkTypeCommand>())
class AddIssuerToTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<AddIssuerToTrustMarkTypeArgs, TrustMarkIssuer, FederationError>(
    commandId = AddIssuerToTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<AddIssuerToTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkIssuer>()
), AddIssuerToTrustMarkTypeCommand {
    private val logger = execution.federationLogger("AddIssuerToTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: AddIssuerToTrustMarkTypeArgs, applyDuring: (AddIssuerToTrustMarkTypeArgs) -> AddIssuerToTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, FederationError> {
        val (tenantId, trustMarkTypeId, issuerIdentifier) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
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
@ContributesBinding(SessionScope::class, binding = binding<RemoveIssuerFromTrustMarkTypeCommand>())
class RemoveIssuerFromTrustMarkTypeCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<RemoveIssuerFromTrustMarkTypeArgs, TrustMarkIssuer, FederationError>(
    commandId = RemoveIssuerFromTrustMarkTypeCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<RemoveIssuerFromTrustMarkTypeArgs>(),
    outputTypeToken = typeToken<TrustMarkIssuer>()
), RemoveIssuerFromTrustMarkTypeCommand {
    private val logger = execution.federationLogger("RemoveIssuerFromTrustMarkTypeCommand")
    private val trustMarkTypeQueries = Persistence.trustMarkTypeQueries
    private val trustMarkIssuerQueries = Persistence.trustMarkIssuerQueries

    override suspend fun doExecute(args: RemoveIssuerFromTrustMarkTypeArgs, applyDuring: (RemoveIssuerFromTrustMarkTypeArgs) -> RemoveIssuerFromTrustMarkTypeArgs): IdkResult<TrustMarkIssuer, FederationError> {
        val (tenantId, trustMarkTypeId, issuerId) = applyDuring(args)
        trustMarkTypeQueries.findByAccountIdAndId(tenantId, trustMarkTypeId).executeAsOneOrNull()
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

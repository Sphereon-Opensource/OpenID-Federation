package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toJsonString
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// CreateSubordinateJwkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateJwkCommand::class)
class CreateSubordinateJwkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateSubordinateJwkArgs, SubordinateJwk, FederationError>(
    id = CreateSubordinateJwkCommand.COMMAND_ID, execution = execution
), CreateSubordinateJwkCommand {
    private val logger = Log.app().withTag("CreateSubordinateJwkCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): IdkResult<SubordinateJwk, FederationError> =
        execute(CreateSubordinateJwkArgs(account, id, jwk))

    override suspend fun doExecute(args: CreateSubordinateJwkArgs, applyDuring: (CreateSubordinateJwkArgs) -> CreateSubordinateJwkArgs): IdkResult<SubordinateJwk, FederationError> {
        val (account, id, jwk) = applyDuring(args)
        logger.info("Creating subordinate JWK for subordinate ID: $id, account: ${account.username}")

        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null) {
            return IdkResult.err(SubordinateNotFoundError(id))
        }

        if (subordinate.account_id != account.id) {
            return IdkResult.err(SubordinateNotFoundError(id))
        }

        return try {
            val createdJwk = subordinateJwkQueries.create(key = jwk.toJsonString(), subordinate_id = subordinate.id)
                .executeAsOne()
            IdkResult.ok(createdJwk.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create subordinate JWK for subordinate ID: $id", e)
            IdkResult.err(ServerError("Failed to create subordinate JWK", e.message, e))
        }
    }
}

// GetSubordinateJwksCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSubordinateJwksCommand::class)
class GetSubordinateJwksCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetSubordinateJwksArgs, Array<SubordinateJwk>, FederationError>(
    id = GetSubordinateJwksCommand.COMMAND_ID, execution = execution
), GetSubordinateJwksCommand {
    private val logger = Log.app().withTag("GetSubordinateJwksCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun getSubordinateJwks(account: Account, id: String): IdkResult<Array<SubordinateJwk>, FederationError> =
        execute(GetSubordinateJwksArgs(account, id))

    override suspend fun doExecute(args: GetSubordinateJwksArgs, applyDuring: (GetSubordinateJwksArgs) -> GetSubordinateJwksArgs): IdkResult<Array<SubordinateJwk>, FederationError> {
        val (account, id) = applyDuring(args)
        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null) {
            return IdkResult.err(SubordinateNotFoundError(id))
        }

        return try {
            val jwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
                .map { it.toDTO() }
                .toTypedArray()
            IdkResult.ok(jwks)
        } catch (e: Exception) {
            logger.error("Failed to retrieve subordinate JWKs for subordinate ID: $id", e)
            IdkResult.err(ServerError("Failed to retrieve subordinate JWKs", e.message, e))
        }
    }
}

// DeleteSubordinateJwkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateJwkCommand::class)
class DeleteSubordinateJwkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteSubordinateJwkArgs, SubordinateJwk, FederationError>(
    id = DeleteSubordinateJwkCommand.COMMAND_ID, execution = execution
), DeleteSubordinateJwkCommand {
    private val logger = Log.app().withTag("DeleteSubordinateJwkCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): IdkResult<SubordinateJwk, FederationError> =
        execute(DeleteSubordinateJwkArgs(account, id, jwkId))

    override suspend fun doExecute(args: DeleteSubordinateJwkArgs, applyDuring: (DeleteSubordinateJwkArgs) -> DeleteSubordinateJwkArgs): IdkResult<SubordinateJwk, FederationError> {
        val (account, id, jwkId) = applyDuring(args)
        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null || subordinate.account_id != account.id) {
            return IdkResult.err(SubordinateNotFoundError(id))
        }

        val subordinateJwk = subordinateJwkQueries.findById(jwkId).executeAsOneOrNull()
        if (subordinateJwk == null || subordinateJwk.subordinate_id != subordinate.id) {
            return IdkResult.err(InvalidRequestError(Constants.SUBORDINATE_JWK_NOT_FOUND))
        }

        return try {
            val deletedJwk = subordinateJwkQueries.delete(subordinateJwk.id).executeAsOne()
            IdkResult.ok(deletedJwk.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate JWK ID: $jwkId", e)
            IdkResult.err(ServerError("Failed to delete subordinate JWK", e.message, e))
        }
    }
}

// FindSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindSubordinateMetadataCommand::class)
class FindSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindSubordinateMetadataArgs, Array<SubordinateMetadata>, FederationError>(
    id = FindSubordinateMetadataCommand.COMMAND_ID, execution = execution
), FindSubordinateMetadataCommand {
    private val logger = Log.app().withTag("FindSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun findSubordinateMetadata(account: Account, subordinateId: String): IdkResult<Array<SubordinateMetadata>, FederationError> =
        execute(FindSubordinateMetadataArgs(account, subordinateId))

    override suspend fun doExecute(args: FindSubordinateMetadataArgs, applyDuring: (FindSubordinateMetadataArgs) -> FindSubordinateMetadataArgs): IdkResult<Array<SubordinateMetadata>, FederationError> {
        val (account, subordinateId) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val metadata = subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()
                .map { it.toDTO() }
                .toTypedArray()
            IdkResult.ok(metadata)
        } catch (e: Exception) {
            logger.error("Failed to find subordinate metadata for subordinate ID: $subordinateId", e)
            IdkResult.err(ServerError("Failed to find subordinate metadata", e.message, e))
        }
    }
}

// CreateSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateMetadataCommand::class)
class CreateSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateSubordinateMetadataArgs, SubordinateMetadata, FederationError>(
    id = CreateSubordinateMetadataCommand.COMMAND_ID, execution = execution
), CreateSubordinateMetadataCommand {
    private val logger = Log.app().withTag("CreateSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): IdkResult<SubordinateMetadata, FederationError> =
        execute(CreateSubordinateMetadataArgs(account, subordinateId, key, metadata))

    override suspend fun doExecute(args: CreateSubordinateMetadataArgs, applyDuring: (CreateSubordinateMetadataArgs) -> CreateSubordinateMetadataArgs): IdkResult<SubordinateMetadata, FederationError> {
        val (account, subordinateId, key, metadata) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        val metadataAlreadyExists = subordinateMetadataQueries
            .findByAccountIdAndSubordinateIdAndKey(account.id, subordinateId, key)
            .executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            return IdkResult.err(InvalidRequestError(Constants.SUBORDINATE_METADATA_ALREADY_EXISTS))
        }

        return try {
            val createdMetadata = subordinateMetadataQueries
                .create(account.id, subordinate.id, key, metadata.toString())
                .executeAsOneOrNull()

            if (createdMetadata != null) {
                IdkResult.ok(createdMetadata.toDTO())
            } else {
                IdkResult.err(ServerError(Constants.FAILED_TO_CREATE_SUBORDINATE_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata for subordinate ID: $subordinateId, key: $key", e)
            IdkResult.err(ServerError("Failed to create subordinate metadata", e.message, e))
        }
    }
}

// DeleteSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateMetadataCommand::class)
class DeleteSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteSubordinateMetadataArgs, SubordinateMetadata, FederationError>(
    id = DeleteSubordinateMetadataCommand.COMMAND_ID, execution = execution
), DeleteSubordinateMetadataCommand {
    private val logger = Log.app().withTag("DeleteSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): IdkResult<SubordinateMetadata, FederationError> =
        execute(DeleteSubordinateMetadataArgs(account, subordinateId, id))

    override suspend fun doExecute(args: DeleteSubordinateMetadataArgs, applyDuring: (DeleteSubordinateMetadataArgs) -> DeleteSubordinateMetadataArgs): IdkResult<SubordinateMetadata, FederationError> {
        val (account, subordinateId, id) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(account.id, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        val metadata = subordinateMetadataQueries
            .findByAccountIdAndSubordinateIdAndId(account.id, subordinate.id, id)
            .executeAsOneOrNull()
        if (metadata == null) {
            return IdkResult.err(InvalidRequestError(Constants.SUBORDINATE_METADATA_NOT_FOUND))
        }

        return try {
            val deletedMetadata = subordinateMetadataQueries.delete(metadata.id).executeAsOneOrNull()
            if (deletedMetadata != null) {
                IdkResult.ok(deletedMetadata.toDTO())
            } else {
                IdkResult.err(ServerError(Constants.SUBORDINATE_METADATA_NOT_FOUND))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id", e)
            IdkResult.err(ServerError("Failed to delete subordinate metadata", e.message, e))
        }
    }
}

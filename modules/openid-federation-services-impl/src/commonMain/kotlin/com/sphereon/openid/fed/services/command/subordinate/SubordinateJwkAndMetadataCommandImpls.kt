package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toJsonString
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// CreateSubordinateJwkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateJwkCommand::class)
class CreateSubordinateJwkCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateSubordinateJwkArgs, SubordinateJwk>(
    commandId = CreateSubordinateJwkCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateSubordinateJwkArgs>(),
    outputTypeToken = typeToken<SubordinateJwk>()
), CreateSubordinateJwkCommand {
    private val logger = Log.app().withTag("CreateSubordinateJwkCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun doExecute(args: CreateSubordinateJwkArgs, applyDuring: (CreateSubordinateJwkArgs) -> CreateSubordinateJwkArgs): IdkResult<SubordinateJwk, IdkError> {
        val (tenantId, id, jwk) = applyDuring(args)
        logger.info("Creating subordinate JWK for subordinate ID: $id, account: ${tenantId}")

        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null) {
            return federationErr(SubordinateNotFoundError(id))
        }

        if (subordinate.account_id != tenantId) {
            return federationErr(SubordinateNotFoundError(id))
        }

        return try {
            val createdJwk = subordinateJwkQueries.create(key = jwk.toJsonString(), subordinate_id = subordinate.id)
                .executeAsOne()
            IdkResult.ok(createdJwk.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create subordinate JWK for subordinate ID: $id", e)
            federationErr(ServerError("Failed to create subordinate JWK", e.message, e))
        }
    }
}

// GetSubordinateJwksCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSubordinateJwksCommand::class)
class GetSubordinateJwksCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetSubordinateJwksArgs, Array<SubordinateJwk>>(
    commandId = GetSubordinateJwksCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetSubordinateJwksArgs>(),
    outputTypeToken = typeToken<Array<SubordinateJwk>>()
), GetSubordinateJwksCommand {
    private val logger = Log.app().withTag("GetSubordinateJwksCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun doExecute(args: GetSubordinateJwksArgs, applyDuring: (GetSubordinateJwksArgs) -> GetSubordinateJwksArgs): IdkResult<Array<SubordinateJwk>, IdkError> {
        val (tenantId, id) = applyDuring(args)
        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null) {
            return federationErr(SubordinateNotFoundError(id))
        }

        return try {
            val jwks = subordinateJwkQueries.findBySubordinateId(subordinate.id).executeAsList()
                .map { it.toDTO() }
                .toTypedArray()
            IdkResult.ok(jwks)
        } catch (e: Exception) {
            logger.error("Failed to retrieve subordinate JWKs for subordinate ID: $id", e)
            federationErr(ServerError("Failed to retrieve subordinate JWKs", e.message, e))
        }
    }
}

// DeleteSubordinateJwkCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateJwkCommand::class)
class DeleteSubordinateJwkCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteSubordinateJwkArgs, SubordinateJwk>(
    commandId = DeleteSubordinateJwkCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteSubordinateJwkArgs>(),
    outputTypeToken = typeToken<SubordinateJwk>()
), DeleteSubordinateJwkCommand {
    private val logger = Log.app().withTag("DeleteSubordinateJwkCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun doExecute(args: DeleteSubordinateJwkArgs, applyDuring: (DeleteSubordinateJwkArgs) -> DeleteSubordinateJwkArgs): IdkResult<SubordinateJwk, IdkError> {
        val (tenantId, id, jwkId) = applyDuring(args)
        val subordinate = subordinateQueries.findById(id).executeAsOneOrNull()
        if (subordinate == null || subordinate.account_id != tenantId) {
            return federationErr(SubordinateNotFoundError(id))
        }

        val subordinateJwk = subordinateJwkQueries.findById(jwkId).executeAsOneOrNull()
        if (subordinateJwk == null || subordinateJwk.subordinate_id != subordinate.id) {
            return federationErr(InvalidRequestError(Constants.SUBORDINATE_JWK_NOT_FOUND))
        }

        return try {
            val deletedJwk = subordinateJwkQueries.delete(subordinateJwk.id).executeAsOne()
            IdkResult.ok(deletedJwk.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate JWK ID: $jwkId", e)
            federationErr(ServerError("Failed to delete subordinate JWK", e.message, e))
        }
    }
}

// FindSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindSubordinateMetadataCommand::class)
class FindSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindSubordinateMetadataArgs, Array<SubordinateMetadata>>(
    commandId = FindSubordinateMetadataCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindSubordinateMetadataArgs>(),
    outputTypeToken = typeToken<Array<SubordinateMetadata>>()
), FindSubordinateMetadataCommand {
    private val logger = Log.app().withTag("FindSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun doExecute(args: FindSubordinateMetadataArgs, applyDuring: (FindSubordinateMetadataArgs) -> FindSubordinateMetadataArgs): IdkResult<Array<SubordinateMetadata>, IdkError> {
        val (tenantId, subordinateId) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(tenantId, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val metadata = subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinate.id)
                .executeAsList()
                .map { it.toDTO() }
                .toTypedArray()
            IdkResult.ok(metadata)
        } catch (e: Exception) {
            logger.error("Failed to find subordinate metadata for subordinate ID: $subordinateId", e)
            federationErr(ServerError("Failed to find subordinate metadata", e.message, e))
        }
    }
}

// CreateSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateMetadataCommand::class)
class CreateSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateSubordinateMetadataArgs, SubordinateMetadata>(
    commandId = CreateSubordinateMetadataCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateSubordinateMetadataArgs>(),
    outputTypeToken = typeToken<SubordinateMetadata>()
), CreateSubordinateMetadataCommand {
    private val logger = Log.app().withTag("CreateSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun doExecute(args: CreateSubordinateMetadataArgs, applyDuring: (CreateSubordinateMetadataArgs) -> CreateSubordinateMetadataArgs): IdkResult<SubordinateMetadata, IdkError> {
        val (tenantId, subordinateId, key, metadata) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(tenantId, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        val metadataAlreadyExists = subordinateMetadataQueries
            .findByAccountIdAndSubordinateIdAndKey(tenantId, subordinateId, key)
            .executeAsOneOrNull()

        if (metadataAlreadyExists != null) {
            return federationErr(InvalidRequestError(Constants.SUBORDINATE_METADATA_ALREADY_EXISTS))
        }

        return try {
            val createdMetadata = subordinateMetadataQueries
                .create(tenantId, subordinate.id, key, metadata.toString())
                .executeAsOneOrNull()

            if (createdMetadata != null) {
                IdkResult.ok(createdMetadata.toDTO())
            } else {
                federationErr(ServerError(Constants.FAILED_TO_CREATE_SUBORDINATE_METADATA))
            }
        } catch (e: Exception) {
            logger.error("Failed to create metadata for subordinate ID: $subordinateId, key: $key", e)
            federationErr(ServerError("Failed to create subordinate metadata", e.message, e))
        }
    }
}

// DeleteSubordinateMetadataCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateMetadataCommand::class)
class DeleteSubordinateMetadataCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteSubordinateMetadataArgs, SubordinateMetadata>(
    commandId = DeleteSubordinateMetadataCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteSubordinateMetadataArgs>(),
    outputTypeToken = typeToken<SubordinateMetadata>()
), DeleteSubordinateMetadataCommand {
    private val logger = Log.app().withTag("DeleteSubordinateMetadataCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateMetadataQueries = Persistence.subordinateMetadataQueries

    override suspend fun doExecute(args: DeleteSubordinateMetadataArgs, applyDuring: (DeleteSubordinateMetadataArgs) -> DeleteSubordinateMetadataArgs): IdkResult<SubordinateMetadata, IdkError> {
        val (tenantId, subordinateId, id) = applyDuring(args)
        val subordinate = subordinateQueries.findByAccountIdAndSubordinateId(tenantId, subordinateId)
            .executeAsOneOrNull()
        if (subordinate == null) {
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        val metadata = subordinateMetadataQueries
            .findByAccountIdAndSubordinateIdAndId(tenantId, subordinate.id, id)
            .executeAsOneOrNull()
        if (metadata == null) {
            return federationErr(InvalidRequestError(Constants.SUBORDINATE_METADATA_NOT_FOUND))
        }

        return try {
            val deletedMetadata = subordinateMetadataQueries.delete(metadata.id).executeAsOneOrNull()
            if (deletedMetadata != null) {
                IdkResult.ok(deletedMetadata.toDTO())
            } else {
                federationErr(ServerError(Constants.SUBORDINATE_METADATA_NOT_FOUND))
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata ID: $id", e)
            federationErr(ServerError("Failed to delete subordinate metadata", e.message, e))
        }
    }
}

package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toDTOs
import com.sphereon.openid.fed.services.mappers.toJwk
import com.sphereon.openid.fed.services.signPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.persistence.models.Subordinate as SubordinateEntity
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata as SubordinateMetadataEntity

// FindSubordinatesByAccountCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindSubordinatesByAccountCommand>())
class FindSubordinatesByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindSubordinatesByAccountArgs, Array<Subordinate>>(
    commandId = FindSubordinatesByAccountCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindSubordinatesByAccountArgs>(),
    outputTypeToken = typeToken<Array<Subordinate>>()
), FindSubordinatesByAccountCommand {
    private val logger = execution.federationLogger("FindSubordinatesByAccountCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: FindSubordinatesByAccountArgs, applyDuring: (FindSubordinatesByAccountArgs) -> FindSubordinatesByAccountArgs): IdkResult<Array<Subordinate>, IdkError> {
        val (tenantId) = applyDuring(args)
        return try {
            val subordinates = subordinateQueries.findByAccountId(tenantId).executeAsList().toTypedArray()
            logger.debug("Found ${subordinates.size} subordinates for account: $tenantId")
            IdkResult.ok(subordinates.toDTOs())
        } catch (e: Exception) {
            logger.error("Failed to find subordinates for account: $tenantId", e)
            federationErr(ServerError("Failed to find subordinates", e.message, e))
        }
    }
}

// FindSubordinatesByAccountAsArrayCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindSubordinatesByAccountAsArrayCommand>())
class FindSubordinatesByAccountAsArrayCommandImpl(
    execution: SessionExecution,
    private val findSubordinatesByAccountCommand: FindSubordinatesByAccountCommand
) : TypedServiceCommandAdapter<FindSubordinatesByAccountAsArrayArgs, Array<String>>(
    commandId = FindSubordinatesByAccountAsArrayCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindSubordinatesByAccountAsArrayArgs>(),
    outputTypeToken = typeToken<Array<String>>()
), FindSubordinatesByAccountAsArrayCommand {

    override suspend fun doExecute(args: FindSubordinatesByAccountAsArrayArgs, applyDuring: (FindSubordinatesByAccountAsArrayArgs) -> FindSubordinatesByAccountAsArrayArgs): IdkResult<Array<String>, IdkError> {
        val (tenantId) = applyDuring(args)
        val result = findSubordinatesByAccountCommand.execute(FindSubordinatesByAccountArgs(tenantId))
        return result.map { subordinates -> subordinates.map { it.identifier }.toTypedArray() }
    }
}

// DeleteSubordinateCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteSubordinateCommand>())
class DeleteSubordinateCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<DeleteSubordinateArgs, Subordinate>(
    commandId = DeleteSubordinateCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteSubordinateArgs>(),
    outputTypeToken = typeToken<Subordinate>()
), DeleteSubordinateCommand {
    private val logger = execution.federationLogger("DeleteSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: DeleteSubordinateArgs, applyDuring: (DeleteSubordinateArgs) -> DeleteSubordinateArgs): IdkResult<Subordinate, IdkError> {
        val (tenantId, subordinateId) = applyDuring(args)
        logger.info("Attempting to delete subordinate ID: $subordinateId for account: $tenantId")

        val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
        if (subordinate == null) {
            logger.error("Subordinate not found with ID: $subordinateId")
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        if (subordinate.account_id != tenantId) {
            logger.warn("Subordinate ID $subordinateId does not belong to account: $tenantId")
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val deletedSubordinate = subordinateQueries.delete(subordinate.id).executeAsOne()
            logger.info("Successfully deleted subordinate ID: $subordinateId")
            IdkResult.ok(deletedSubordinate.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate ID: $subordinateId", e)
            federationErr(ServerError("Failed to delete subordinate", e.message, e))
        }
    }
}

// CreateSubordinateCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateSubordinateCommand>())
class CreateSubordinateCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateSubordinateArgs, Subordinate>(
    commandId = CreateSubordinateCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateSubordinateArgs>(),
    outputTypeToken = typeToken<Subordinate>()
), CreateSubordinateCommand {
    private val logger = execution.federationLogger("CreateSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: CreateSubordinateArgs, applyDuring: (CreateSubordinateArgs) -> CreateSubordinateArgs): IdkResult<Subordinate, IdkError> {
        val (tenantId, createRequest) = applyDuring(args)
        logger.info("Creating new subordinate for account: $tenantId")

        val subordinateAlreadyExists = subordinateQueries
            .findByAccountIdAndIdentifier(tenantId, createRequest.identifier)
            .executeAsList()

        if (subordinateAlreadyExists.isNotEmpty()) {
            logger.warn("Subordinate already exists with identifier: ${createRequest.identifier}")
            return federationErr(InvalidRequestError(Constants.SUBORDINATE_ALREADY_EXISTS))
        }

        return try {
            val createdSubordinate = subordinateQueries.create(tenantId, createRequest.identifier).executeAsOne()
            logger.info("Successfully created subordinate with ID: ${createdSubordinate.id}")
            IdkResult.ok(createdSubordinate.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create subordinate for account: $tenantId", e)
            federationErr(ServerError("Failed to create subordinate", e.message, e))
        }
    }
}

// GetSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetSubordinateStatementCommand>())
class GetSubordinateStatementCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver
) : TypedServiceCommandAdapter<GetSubordinateStatementArgs, SubordinateStatement>(
    commandId = GetSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetSubordinateStatementArgs>(),
    outputTypeToken = typeToken<SubordinateStatement>()
), GetSubordinateStatementCommand {
    private val logger = execution.federationLogger("GetSubordinateStatementCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun doExecute(args: GetSubordinateStatementArgs, applyDuring: (GetSubordinateStatementArgs) -> GetSubordinateStatementArgs): IdkResult<SubordinateStatement, IdkError> {
        val (tenantId, subordinateId) = applyDuring(args)
        logger.info("Generating subordinate statement for ID: $subordinateId, account: $tenantId")

        val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
        if (subordinate == null) {
            logger.error("Subordinate not found with ID: $subordinateId")
            return federationErr(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val subordinateJwks = subordinateJwkQueries
                .findBySubordinateId(subordinate.id)
                .executeAsList()
                .map { it.toJwk() }

            val subordinateMetadataList = Persistence.subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinate.id)
                .executeAsList()

            // Load constraints for this subordinate
            val constraintEntity = Persistence.subordinateConstraintQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinate.id)
                .executeAsOneOrNull()
            val constraints = constraintEntity?.let {
                try { Json.decodeFromString<Constraints>(it.constraints) } catch (_: Exception) { null }
            }

            buildSubordinateStatement(tenantId, subordinate, subordinateJwks, subordinateMetadataList, constraints)
        } catch (e: Exception) {
            logger.error("Failed to generate subordinate statement for ID: $subordinateId", e)
            federationErr(ServerError("Failed to generate subordinate statement", e.message, e))
        }
    }

    private suspend fun buildSubordinateStatement(
        tenantId: String,
        subordinate: SubordinateEntity,
        subordinateJwks: List<com.sphereon.openid.fed.openapi.models.Jwk>,
        subordinateMetadataList: List<SubordinateMetadataEntity>,
        constraints: Constraints? = null
    ): IdkResult<SubordinateStatement, IdkError> {
        val accountIdentifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        val currentTimeSeconds = (System.currentTimeMillis() / 1000).toDouble()
        val expirationTime = currentTimeSeconds + 3600 * 24 * 365

        check(accountIdentifier.isNotEmpty()) { "Account identifier is empty" }

        val statement = SubordinateStatementObjectBuilder()
            .iss(accountIdentifier)
            .sub(subordinate.identifier)
            .iat(currentTimeSeconds)
            .exp(expirationTime)
            .sourceEndpoint("$accountIdentifier/fetch")

        subordinateJwks.forEach { statement.jwks(it) }
        subordinateMetadataList.forEach {
            val metadataJson = Json.parseToJsonElement(it.metadata).jsonObject
            statement.metadata(Pair(it.key, metadataJson))
        }

        if (constraints != null) {
            statement.constraints(constraints)
        }

        return IdkResult.ok(statement.build())
    }
}

// PublishSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PublishSubordinateStatementCommand>())
class PublishSubordinateStatementCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val jwkService: JwkService,
    private val jwtService: JwtService,
    private val getSubordinateStatementCommand: GetSubordinateStatementCommand
) : TypedServiceCommandAdapter<PublishSubordinateStatementArgs, String>(
    commandId = PublishSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<PublishSubordinateStatementArgs>(),
    outputTypeToken = typeToken<String>()
), PublishSubordinateStatementCommand {
    private val logger = execution.federationLogger("PublishSubordinateStatementCommand")
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(args: PublishSubordinateStatementArgs, applyDuring: (PublishSubordinateStatementArgs) -> PublishSubordinateStatementArgs): IdkResult<String, IdkError> {
        val (tenantId, subordinateId, dryRun, kmsKeyRef, kid) = applyDuring(args)
        logger.info("Publishing subordinate statement for ID: $subordinateId, account: $tenantId (dryRun: $dryRun)")

        val statementResult = getSubordinateStatementCommand.execute(GetSubordinateStatementArgs(tenantId, subordinateId))
        if (statementResult.isErr) return statementResult.error.asErrorResult()
        val subordinateStatement = statementResult.value

        val keysResult = jwkService.getAssertedKeysForAccount(tenantId, includeRevoked = false, kmsKeyRef = kmsKeyRef, kid = kid).toIdkErrorResult()
        if (keysResult.isErr) return keysResult.error.asErrorResult()
        val keys = keysResult.value

        return try {
            val key = keys[0]
            val header = JwtHeader(typ = "entity-statement+jwt", kid = key.kid, alg = key.alg ?: "RS256")
            val jwtResult = jwtService.signPayload(
                payload = subordinateStatement,
                header = header,
                kid = key.kid,
                kmsKeyRef = key.kmsKeyRef,
                kmsProviderId = key.kms,
            ).toIdkErrorResult()

            if (jwtResult.isErr) {
                return jwtResult
            }
            val jwt = jwtResult.value

            if (dryRun == true) {
                return IdkResult.ok(jwt)
            }

            val accountIdentifier = tenantContextResolver.resolveIdentifier(tenantId)
                ?: return federationErr(TenantNotFoundError(tenantId))

            subordinateStatementQueries.create(
                subordinate_id = subordinateId,
                iss = accountIdentifier,
                sub = subordinateStatement.sub,
                statement = jwt,
                expires_at = subordinateStatement.exp.toLong()
            ).executeAsOne()
            IdkResult.ok(jwt)
        } catch (e: Exception) {
            logger.error("Failed to publish subordinate statement for ID: $subordinateId", e)
            federationErr(ServerError("Failed to publish subordinate statement", e.message, e))
        }
    }
}

// FetchSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FetchSubordinateStatementCommand>())
class FetchSubordinateStatementCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FetchSubordinateStatementArgs, String>(
    commandId = FetchSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FetchSubordinateStatementArgs>(),
    outputTypeToken = typeToken<String>()
), FetchSubordinateStatementCommand {
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(args: FetchSubordinateStatementArgs, applyDuring: (FetchSubordinateStatementArgs) -> FetchSubordinateStatementArgs): IdkResult<String, IdkError> {
        val (iss, sub) = applyDuring(args)
        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return federationErr(InvalidRequestError(Constants.SUBORDINATE_STATEMENT_NOT_FOUND))
        return IdkResult.ok(statement.statement)
    }
}

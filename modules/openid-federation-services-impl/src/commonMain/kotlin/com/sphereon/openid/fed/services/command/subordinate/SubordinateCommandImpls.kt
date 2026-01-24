package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toDTOs
import com.sphereon.openid.fed.services.mappers.toJwk
import com.sphereon.openid.fed.services.signPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.persistence.models.Subordinate as SubordinateEntity
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata as SubordinateMetadataEntity

// FindSubordinatesByAccountCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindSubordinatesByAccountCommand::class)
class FindSubordinatesByAccountCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FindSubordinatesByAccountArgs, Array<Subordinate>, FederationError>(
    id = FindSubordinatesByAccountCommand.COMMAND_ID, execution = execution
), FindSubordinatesByAccountCommand {
    private val logger = Log.app().withTag("FindSubordinatesByAccountCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun findSubordinatesByAccount(account: Account): IdkResult<Array<Subordinate>, FederationError> =
        execute(FindSubordinatesByAccountArgs(account), execution.sessionContext)

    override suspend fun doExecute(args: FindSubordinatesByAccountArgs, sessionContext: SessionContext, applyDuring: (FindSubordinatesByAccountArgs) -> FindSubordinatesByAccountArgs): IdkResult<Array<Subordinate>, FederationError> {
        val (account) = applyDuring(args)
        return try {
            val subordinates = subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
            logger.debug("Found ${subordinates.size} subordinates for account: ${account.username}")
            IdkResult.ok(subordinates.toDTOs())
        } catch (e: Exception) {
            logger.error("Failed to find subordinates for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to find subordinates", e.message, e))
        }
    }
}

// FindSubordinatesByAccountAsArrayCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindSubordinatesByAccountAsArrayCommand::class)
class FindSubordinatesByAccountAsArrayCommandImpl(
    execution: SessionExecution,
    private val findSubordinatesByAccountCommand: FindSubordinatesByAccountCommand
) : ExecutionScopedCommandAdapter<FindSubordinatesByAccountAsArrayArgs, Array<String>, FederationError>(
    id = FindSubordinatesByAccountAsArrayCommand.COMMAND_ID, execution = execution
), FindSubordinatesByAccountAsArrayCommand {

    override suspend fun findSubordinatesByAccountAsArray(account: Account): IdkResult<Array<String>, FederationError> =
        execute(FindSubordinatesByAccountAsArrayArgs(account), execution.sessionContext)

    override suspend fun doExecute(args: FindSubordinatesByAccountAsArrayArgs, sessionContext: SessionContext, applyDuring: (FindSubordinatesByAccountAsArrayArgs) -> FindSubordinatesByAccountAsArrayArgs): IdkResult<Array<String>, FederationError> {
        val (account) = applyDuring(args)
        val result = findSubordinatesByAccountCommand.findSubordinatesByAccount(account)
        return result.map { subordinates -> subordinates.map { it.identifier }.toTypedArray() }
    }
}

// DeleteSubordinateCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = DeleteSubordinateCommand::class)
class DeleteSubordinateCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<DeleteSubordinateArgs, Subordinate, FederationError>(
    id = DeleteSubordinateCommand.COMMAND_ID, execution = execution
), DeleteSubordinateCommand {
    private val logger = Log.app().withTag("DeleteSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun deleteSubordinate(account: Account, id: String): IdkResult<Subordinate, FederationError> =
        execute(DeleteSubordinateArgs(account, id), execution.sessionContext)

    override suspend fun doExecute(args: DeleteSubordinateArgs, sessionContext: SessionContext, applyDuring: (DeleteSubordinateArgs) -> DeleteSubordinateArgs): IdkResult<Subordinate, FederationError> {
        val (account, subordinateId) = applyDuring(args)
        logger.info("Attempting to delete subordinate ID: $subordinateId for account: ${account.username}")

        val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
        if (subordinate == null) {
            logger.error("Subordinate not found with ID: $subordinateId")
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        if (subordinate.account_id != account.id) {
            logger.warn("Subordinate ID $subordinateId does not belong to account: ${account.username}")
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val deletedSubordinate = subordinateQueries.delete(subordinate.id).executeAsOne()
            logger.info("Successfully deleted subordinate ID: $subordinateId")
            IdkResult.ok(deletedSubordinate.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to delete subordinate ID: $subordinateId", e)
            IdkResult.err(ServerError("Failed to delete subordinate", e.message, e))
        }
    }
}

// CreateSubordinateCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateSubordinateCommand::class)
class CreateSubordinateCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateSubordinateArgs, Subordinate, FederationError>(
    id = CreateSubordinateCommand.COMMAND_ID, execution = execution
), CreateSubordinateCommand {
    private val logger = Log.app().withTag("CreateSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): IdkResult<Subordinate, FederationError> =
        execute(CreateSubordinateArgs(account, subordinateDTO), execution.sessionContext)

    override suspend fun doExecute(args: CreateSubordinateArgs, sessionContext: SessionContext, applyDuring: (CreateSubordinateArgs) -> CreateSubordinateArgs): IdkResult<Subordinate, FederationError> {
        val (account, createRequest) = applyDuring(args)
        logger.info("Creating new subordinate for account: ${account.username}")

        val subordinateAlreadyExists = subordinateQueries
            .findByAccountIdAndIdentifier(account.id, createRequest.identifier)
            .executeAsList()

        if (subordinateAlreadyExists.isNotEmpty()) {
            logger.warn("Subordinate already exists with identifier: ${createRequest.identifier}")
            return IdkResult.err(InvalidRequestError(Constants.SUBORDINATE_ALREADY_EXISTS))
        }

        return try {
            val createdSubordinate = subordinateQueries.create(account.id, createRequest.identifier).executeAsOne()
            logger.info("Successfully created subordinate with ID: ${createdSubordinate.id}")
            IdkResult.ok(createdSubordinate.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create subordinate for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to create subordinate", e.message, e))
        }
    }
}

// GetSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSubordinateStatementCommand::class)
class GetSubordinateStatementCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService
) : ExecutionScopedCommandAdapter<GetSubordinateStatementArgs, SubordinateStatement, FederationError>(
    id = GetSubordinateStatementCommand.COMMAND_ID, execution = execution
), GetSubordinateStatementCommand {
    private val logger = Log.app().withTag("GetSubordinateStatementCommand")
    private val subordinateQueries = Persistence.subordinateQueries
    private val subordinateJwkQueries = Persistence.subordinateJwkQueries

    override suspend fun getSubordinateStatement(account: Account, id: String): IdkResult<SubordinateStatement, FederationError> =
        execute(GetSubordinateStatementArgs(account, id), execution.sessionContext)

    override suspend fun doExecute(args: GetSubordinateStatementArgs, sessionContext: SessionContext, applyDuring: (GetSubordinateStatementArgs) -> GetSubordinateStatementArgs): IdkResult<SubordinateStatement, FederationError> {
        val (account, subordinateId) = applyDuring(args)
        logger.info("Generating subordinate statement for ID: $subordinateId, account: ${account.username}")

        val subordinate = subordinateQueries.findById(subordinateId).executeAsOneOrNull()
        if (subordinate == null) {
            logger.error("Subordinate not found with ID: $subordinateId")
            return IdkResult.err(SubordinateNotFoundError(subordinateId))
        }

        return try {
            val subordinateJwks = subordinateJwkQueries
                .findBySubordinateId(subordinate.id)
                .executeAsList()
                .map { it.toJwk() }

            val subordinateMetadataList = Persistence.subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(account.id, subordinate.id)
                .executeAsList()

            buildSubordinateStatement(account, subordinate, subordinateJwks, subordinateMetadataList)
        } catch (e: Exception) {
            logger.error("Failed to generate subordinate statement for ID: $subordinateId", e)
            IdkResult.err(ServerError("Failed to generate subordinate statement", e.message, e))
        }
    }

    private suspend fun buildSubordinateStatement(
        account: Account,
        subordinate: SubordinateEntity,
        subordinateJwks: List<com.sphereon.openid.fed.openapi.models.Jwk>,
        subordinateMetadataList: List<SubordinateMetadataEntity>
    ): IdkResult<SubordinateStatement, FederationError> {
        val accountIdentifierResult = accountService.getAccountIdentifierByAccount(account)
        if (accountIdentifierResult.isErr) return accountIdentifierResult.error.asErrorResult()
        val accountIdentifier = accountIdentifierResult.value

        val currentTimeSeconds = (System.currentTimeMillis() / 1000).toInt()
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

        return IdkResult.ok(statement.build())
    }
}

// PublishSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PublishSubordinateStatementCommand::class)
class PublishSubordinateStatementCommandImpl(
    execution: SessionExecution,
    private val accountService: AccountService,
    private val jwkService: JwkService,
    private val jwtService: JwtService,
    private val getSubordinateStatementCommand: GetSubordinateStatementCommand
) : ExecutionScopedCommandAdapter<PublishSubordinateStatementArgs, String, FederationError>(
    id = PublishSubordinateStatementCommand.COMMAND_ID, execution = execution
), PublishSubordinateStatementCommand {
    private val logger = Log.app().withTag("PublishSubordinateStatementCommand")
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun publishSubordinateStatement(
        account: Account,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): IdkResult<String, FederationError> =
        execute(PublishSubordinateStatementArgs(account, id, dryRun, kmsKeyRef, kid), execution.sessionContext)

    override suspend fun doExecute(args: PublishSubordinateStatementArgs, sessionContext: SessionContext, applyDuring: (PublishSubordinateStatementArgs) -> PublishSubordinateStatementArgs): IdkResult<String, FederationError> {
        val (account, subordinateId, dryRun, kmsKeyRef, kid) = applyDuring(args)
        logger.info("Publishing subordinate statement for ID: $subordinateId, account: ${account.username} (dryRun: $dryRun)")

        val statementResult = getSubordinateStatementCommand.getSubordinateStatement(account, subordinateId)
        if (statementResult.isErr) return statementResult.error.asErrorResult()
        val subordinateStatement = statementResult.value

        val keysResult = jwkService.getAssertedKeysForAccount(account, includeRevoked = false, kmsKeyRef = kmsKeyRef, kid = kid)
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
            )

            if (jwtResult.isErr) {
                return jwtResult
            }
            val jwt = jwtResult.value

            if (dryRun == true) {
                return IdkResult.ok(jwt)
            }

            val accountIdentifierResult = accountService.getAccountIdentifierByAccount(account)
            if (accountIdentifierResult.isErr) return accountIdentifierResult.error.asErrorResult()
            val accountIdentifier = accountIdentifierResult.value

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
            IdkResult.err(ServerError("Failed to publish subordinate statement", e.message, e))
        }
    }
}

// FetchSubordinateStatementCommand Implementation
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FetchSubordinateStatementCommand::class)
class FetchSubordinateStatementCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<FetchSubordinateStatementArgs, String, FederationError>(
    id = FetchSubordinateStatementCommand.COMMAND_ID, execution = execution
), FetchSubordinateStatementCommand {
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun fetchSubordinateStatement(iss: String, sub: String): IdkResult<String, FederationError> =
        execute(FetchSubordinateStatementArgs(iss, sub), execution.sessionContext)

    override suspend fun doExecute(args: FetchSubordinateStatementArgs, sessionContext: SessionContext, applyDuring: (FetchSubordinateStatementArgs) -> FetchSubordinateStatementArgs): IdkResult<String, FederationError> {
        val (iss, sub) = applyDuring(args)
        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return IdkResult.err(InvalidRequestError(Constants.SUBORDINATE_STATEMENT_NOT_FOUND))
        return IdkResult.ok(statement.statement)
    }
}

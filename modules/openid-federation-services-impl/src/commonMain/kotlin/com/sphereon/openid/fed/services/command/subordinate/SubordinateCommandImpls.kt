package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.core.error.FederationError

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
import com.sphereon.openid.fed.common.builder.FederationEndpointUrls
import com.sphereon.openid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toDTO
import com.sphereon.openid.fed.services.mappers.toDTOs
import com.sphereon.openid.fed.services.mappers.toJwk
import com.sphereon.openid.fed.services.signPayload
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
) : TypedServiceCommandAdapter<FindSubordinatesByAccountArgs, Array<Subordinate>, FederationError>(
    commandId = FindSubordinatesByAccountCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindSubordinatesByAccountArgs>(),
    outputTypeToken = typeToken<Array<Subordinate>>()
), FindSubordinatesByAccountCommand {
    private val logger = execution.federationLogger("FindSubordinatesByAccountCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: FindSubordinatesByAccountArgs, applyDuring: (FindSubordinatesByAccountArgs) -> FindSubordinatesByAccountArgs): IdkResult<Array<Subordinate>, FederationError> {
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
) : TypedServiceCommandAdapter<FindSubordinatesByAccountAsArrayArgs, Array<String>, FederationError>(
    commandId = FindSubordinatesByAccountAsArrayCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FindSubordinatesByAccountAsArrayArgs>(),
    outputTypeToken = typeToken<Array<String>>()
), FindSubordinatesByAccountAsArrayCommand {

    override suspend fun doExecute(args: FindSubordinatesByAccountAsArrayArgs, applyDuring: (FindSubordinatesByAccountAsArrayArgs) -> FindSubordinatesByAccountAsArrayArgs): IdkResult<Array<String>, FederationError> {
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
) : TypedServiceCommandAdapter<DeleteSubordinateArgs, Subordinate, FederationError>(
    commandId = DeleteSubordinateCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<DeleteSubordinateArgs>(),
    outputTypeToken = typeToken<Subordinate>()
), DeleteSubordinateCommand {
    private val logger = execution.federationLogger("DeleteSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: DeleteSubordinateArgs, applyDuring: (DeleteSubordinateArgs) -> DeleteSubordinateArgs): IdkResult<Subordinate, FederationError> {
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
) : TypedServiceCommandAdapter<CreateSubordinateArgs, Subordinate, FederationError>(
    commandId = CreateSubordinateCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<CreateSubordinateArgs>(),
    outputTypeToken = typeToken<Subordinate>()
), CreateSubordinateCommand {
    private val logger = execution.federationLogger("CreateSubordinateCommand")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun doExecute(args: CreateSubordinateArgs, applyDuring: (CreateSubordinateArgs) -> CreateSubordinateArgs): IdkResult<Subordinate, FederationError> {
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
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetSubordinateStatementCommand>())
class GetSubordinateStatementCommandImpl internal constructor(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val subordinateQueries: SubordinateQueries,
    private val subordinateJwkQueries: SubordinateJwkQueries,
    private val subordinateMetadataQueries: SubordinateMetadataQueries,
    private val subordinateConstraintQueries: SubordinateConstraintQueries,
    private val metadataPolicyQueries: MetadataPolicyQueries,
    private val configBinder: OidfConfigBinder,
) : TypedServiceCommandAdapter<GetSubordinateStatementArgs, SubordinateStatement, FederationError>(
    commandId = GetSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetSubordinateStatementArgs>(),
    outputTypeToken = typeToken<SubordinateStatement>()
), GetSubordinateStatementCommand {
    @Inject
    constructor(execution: SessionExecution, tenantContextResolver: TenantContextResolver, configBinder: OidfConfigBinder) : this(
        execution,
        tenantContextResolver,
        Persistence.subordinateQueries,
        Persistence.subordinateJwkQueries,
        Persistence.subordinateMetadataQueries,
        Persistence.subordinateConstraintQueries,
        Persistence.metadataPolicyQueries,
        configBinder,
    )

    private val logger = execution.federationLogger("GetSubordinateStatementCommand")

    override suspend fun doExecute(args: GetSubordinateStatementArgs, applyDuring: (GetSubordinateStatementArgs) -> GetSubordinateStatementArgs): IdkResult<SubordinateStatement, FederationError> {
        val (tenantId, subordinateId) = applyDuring(args)
        logger.info("Generating subordinate statement for ID: $subordinateId, account: $tenantId")

        return try {
            val subordinate = subordinateQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinateId)
                .executeAsOneOrNull()
            if (subordinate == null) {
                logger.error("Subordinate not found with ID: $subordinateId")
                return federationErr(SubordinateNotFoundError(subordinateId))
            }

            val subordinateJwks = subordinateJwkQueries
                .findBySubordinateId(subordinate.id)
                .executeAsList()
                .map { it.toJwk() }

            val subordinateMetadataList = subordinateMetadataQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinate.id)
                .executeAsList()

            // Load constraints for this subordinate
            val constraintEntity = subordinateConstraintQueries
                .findByAccountIdAndSubordinateId(tenantId, subordinate.id)
                .executeAsOneOrNull()
            val constraints = constraintEntity?.let {
                val parsed = EntityStatementValidation.parseConstraints(Json.parseToJsonElement(it.constraints))
                parsed.constraints ?: return federationErr(
                    ServerError("Stored subordinate constraints are invalid", parsed.reason)
                )
            }

            buildSubordinateStatement(tenantId, subordinate, subordinateJwks, subordinateMetadataList, constraints)
        } catch (e: CancellationException) {
            throw e
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
    ): IdkResult<SubordinateStatement, FederationError> {
        val accountIdentifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))

        val lifetimeSeconds = configBinder.getFederationConfig().statementLifetimeSeconds
            ?: return federationErr(ServerError("Subordinate statement lifetime is not configured"))
        val currentTimeSeconds = (System.currentTimeMillis() / 1000).toDouble()
        val expirationTime = currentTimeSeconds + lifetimeSeconds

        check(accountIdentifier.isNotEmpty()) { "Account identifier is empty" }

        val statement = SubordinateStatementObjectBuilder()
            .iss(accountIdentifier)
            .sub(subordinate.identifier)
            .iat(currentTimeSeconds)
            .exp(expirationTime)
            // Must equal published federation_fetch_endpoint (OIDFed 1.1 §3.1.3 / §5.1.1)
            .sourceEndpoint(FederationEndpointUrls.fetch(accountIdentifier))

        if (subordinateJwks.isEmpty()) {
            return federationErr(InvalidRequestError("Subordinate ${subordinate.identifier} has no public keys to vouch for"))
        }
        subordinateJwks.forEach { statement.jwks(it) }
        subordinateMetadataList.forEach {
            val metadataJson = Json.parseToJsonElement(it.metadata).jsonObject
            statement.metadata(Pair(it.key, metadataJson))
        }

        // Account-level metadata policies → Subordinate Statement metadata_policy (OIDFed 1.1 §3.1.3)
        // Each stored policy key is an Entity Type Identifier; policy body is claim → operators.
        metadataPolicyQueries.findByAccountId(tenantId)
            .executeAsList()
            .forEach { policyRow ->
                val policyJson = Json.parseToJsonElement(policyRow.policy).jsonObject
                val checked = MetadataPolicyOperators.mergePolicies(
                    JsonObject(emptyMap()),
                    JsonObject(mapOf(policyRow.key to policyJson)),
                )
                if (checked is MetadataPolicyOperators.PolicyMergeResult.Error) {
                    return federationErr(ServerError("Stored metadata policy for ${policyRow.key} is invalid", checked.reason))
                }
                statement.metadataPolicy(Pair(policyRow.key, policyJson))
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
    private val jwtService: JwtService,
    private val getSubordinateStatementCommand: GetSubordinateStatementCommand,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
) : TypedServiceCommandAdapter<PublishSubordinateStatementArgs, String, FederationError>(
    commandId = PublishSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<PublishSubordinateStatementArgs>(),
    outputTypeToken = typeToken<String>()
), PublishSubordinateStatementCommand {
    private val logger = execution.federationLogger("PublishSubordinateStatementCommand")
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(args: PublishSubordinateStatementArgs, applyDuring: (PublishSubordinateStatementArgs) -> PublishSubordinateStatementArgs): IdkResult<String, FederationError> {
        val (tenantId, subordinateId, dryRun, kmsKeyRef, kid) = applyDuring(args)
        logger.info("Publishing subordinate statement for ID: $subordinateId, account: $tenantId (dryRun: $dryRun)")

        val statementResult = getSubordinateStatementCommand.execute(GetSubordinateStatementArgs(tenantId, subordinateId))
        if (statementResult.isErr) return statementResult.error.asErrorResult()
        val subordinateStatement = statementResult.value

        val accountIdentifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return federationErr(TenantNotFoundError(tenantId))
        if (subordinateStatement.iss != accountIdentifier) {
            return federationErr(InvalidRequestError("Subordinate statement issuer must be this superior"))
        }
        // The superior signs with its own persisted signing-key selection; callers cannot choose another key.
        val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(tenantId, accountIdentifier))
        if (resolved.isErr) return resolved.error.asErrorResult()
        val key = resolved.value
        if ((kid != null && kid != key.kid) || (kmsKeyRef != null && kmsKeyRef != key.kmsKeyRef)) {
            return federationErr(InvalidRequestError("Requested key differs from the superior's selected signing key"))
        }

        return try {
            val header = JwtHeader(typ = "entity-statement+jwt", kid = key.kid, alg = key.alg)
            val jwtResult = jwtService.signPayload(
                payload = subordinateStatement,
                header = header,
                kid = key.kid,
                kmsKeyRef = key.kmsKeyRef,
                kmsProviderId = key.kms,
            )

            if (jwtResult.isErr) {
                return jwtResult
            }
            val jwt = jwtResult.value

            if (dryRun == true) {
                return IdkResult.ok(jwt)
            }

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
) : TypedServiceCommandAdapter<FetchSubordinateStatementArgs, String, FederationError>(
    commandId = FetchSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<FetchSubordinateStatementArgs>(),
    outputTypeToken = typeToken<String>()
), FetchSubordinateStatementCommand {
    private val subordinateStatementQueries = Persistence.subordinateStatementQueries

    override suspend fun doExecute(args: FetchSubordinateStatementArgs, applyDuring: (FetchSubordinateStatementArgs) -> FetchSubordinateStatementArgs): IdkResult<String, FederationError> {
        val (iss, sub) = applyDuring(args)
        val statement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: return federationErr(InvalidRequestError(Constants.SUBORDINATE_STATEMENT_NOT_FOUND))
        return IdkResult.ok(statement.statement)
    }
}

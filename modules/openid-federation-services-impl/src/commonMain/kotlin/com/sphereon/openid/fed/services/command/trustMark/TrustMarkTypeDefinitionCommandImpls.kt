package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.core.error.TrustMarkTypeVersionConflictError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeRevision
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException

private val TRUST_MARK_TYPE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Trust Mark type ids are UUIDs; anything else cannot name a stored type. */
internal fun isTrustMarkTypeId(id: String): Boolean = TRUST_MARK_TYPE_ID.matches(id)

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<UpdateTrustMarkTypeDefinitionCommand>())
class UpdateTrustMarkTypeDefinitionCommandImpl internal constructor(
    execution: SessionExecution,
    private val trustMarkTypeQueries: TrustMarkTypeQueries,
) : TypedServiceCommandAdapter<UpdateTrustMarkTypeDefinitionArgs, TrustMarkType, FederationError>(
    commandId = UpdateTrustMarkTypeDefinitionCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<UpdateTrustMarkTypeDefinitionArgs>(),
    outputTypeToken = typeToken<TrustMarkType>(),
), UpdateTrustMarkTypeDefinitionCommand {
    private val logger = execution.federationLogger("UpdateTrustMarkTypeDefinitionCommand")

    @Inject
    constructor(execution: SessionExecution) : this(execution, Persistence.trustMarkTypeQueries)

    override suspend fun doExecute(
        args: UpdateTrustMarkTypeDefinitionArgs,
        applyDuring: (UpdateTrustMarkTypeDefinitionArgs) -> UpdateTrustMarkTypeDefinitionArgs,
    ): IdkResult<TrustMarkType, FederationError> {
        val (tenantId, id, expectedVersion, requested) = applyDuring(args)
        if (!isTrustMarkTypeId(id)) return federationErr(TrustMarkTypeNotFoundError(id))
        val validated = TrustMarkTypeDefinitions.validate(requested)
        if (validated.isErr) return federationErr(validated.error)
        val definition = TrustMarkTypeDefinitions.encode(validated.value)

        return try {
            val updated = trustMarkTypeQueries.transactionWithResult {
                val current = trustMarkTypeQueries.findByAccountIdAndId(tenantId, id).executeAsOneOrNull()
                    ?: throw FederationErrorException(TrustMarkTypeNotFoundError(id))
                if (current.version != expectedVersion) {
                    throw FederationErrorException(TrustMarkTypeVersionConflictError(id, expectedVersion, current.version))
                }
                // The version guard in the update refuses a concurrent update that committed after the read above.
                val type = trustMarkTypeQueries.updateDefinition(definition, tenantId, id, expectedVersion).executeAsOneOrNull()
                    ?: run {
                        val latest = trustMarkTypeQueries.findByAccountIdAndId(tenantId, id).executeAsOneOrNull()
                            ?: throw FederationErrorException(TrustMarkTypeNotFoundError(id))
                        throw FederationErrorException(TrustMarkTypeVersionConflictError(id, expectedVersion, latest.version))
                    }
                trustMarkTypeQueries.insertRevision(type.id, type.version, type.definition).executeAsOne()
                type
            }
            logger.info("Updated the definition of trust mark type $id to version ${updated.version}")
            IdkResult.ok(updated.toDTO())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to update trust mark type definition", failure)
            federationErr(ServerError("Failed to update trust mark type definition", failure.message, failure))
        }
    }
}

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListTrustMarkTypeRevisionsCommand>())
class ListTrustMarkTypeRevisionsCommandImpl internal constructor(
    execution: SessionExecution,
    private val trustMarkTypeQueries: TrustMarkTypeQueries,
) : TypedServiceCommandAdapter<ListTrustMarkTypeRevisionsArgs, List<TrustMarkTypeRevision>, FederationError>(
    commandId = ListTrustMarkTypeRevisionsCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ListTrustMarkTypeRevisionsArgs>(),
    outputTypeToken = typeToken<List<TrustMarkTypeRevision>>(),
), ListTrustMarkTypeRevisionsCommand {
    private val logger = execution.federationLogger("ListTrustMarkTypeRevisionsCommand")

    @Inject
    constructor(execution: SessionExecution) : this(execution, Persistence.trustMarkTypeQueries)

    override suspend fun doExecute(
        args: ListTrustMarkTypeRevisionsArgs,
        applyDuring: (ListTrustMarkTypeRevisionsArgs) -> ListTrustMarkTypeRevisionsArgs,
    ): IdkResult<List<TrustMarkTypeRevision>, FederationError> {
        val (tenantId, id) = applyDuring(args)
        if (!isTrustMarkTypeId(id)) return federationErr(TrustMarkTypeNotFoundError(id))
        return try {
            val revisions = trustMarkTypeQueries.transactionWithResult {
                trustMarkTypeQueries.findByAccountIdAndIdIncludingDeleted(tenantId, id).executeAsOneOrNull()
                    ?: throw FederationErrorException(TrustMarkTypeNotFoundError(id))
                trustMarkTypeQueries.findRevisionsByTypeId(id).executeAsList().map { it.toDTO() }
            }
            IdkResult.ok(revisions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to list trust mark type revisions", failure)
            federationErr(ServerError("Failed to list trust mark type revisions", failure.message, failure))
        }
    }
}

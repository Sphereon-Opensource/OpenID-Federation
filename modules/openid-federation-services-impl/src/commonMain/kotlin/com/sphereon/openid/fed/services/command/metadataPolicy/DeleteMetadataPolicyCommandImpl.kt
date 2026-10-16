package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.MetadataPolicyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the DeleteMetadataPolicyCommand.
 * Deletes a metadata policy entry.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteMetadataPolicyCommand>())
class DeleteMetadataPolicyCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: com.sphereon.openid.fed.persistence.models.AccountQueries,
    private val metadataPolicyQueries: com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries,
    private val revisionQueries: com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries,
) : TypedServiceCommandAdapter<DeleteMetadataPolicyArgs, MetadataPolicy, FederationError>(
    commandId = DeleteMetadataPolicyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DeleteMetadataPolicyArgs>(),
    outputTypeToken = typeToken<MetadataPolicy>()
), DeleteMetadataPolicyCommand {

    private val logger = execution.federationLogger("DeleteMetadataPolicyCommand")
    @Inject
    constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries, Persistence.metadataPolicyQueries, Persistence.accountMetadataPolicyRevisionQueries)

    override suspend fun doExecute(
        args: DeleteMetadataPolicyArgs,
        applyDuring: (DeleteMetadataPolicyArgs) -> DeleteMetadataPolicyArgs
    ): IdkResult<MetadataPolicy, FederationError> {
        return try {
            val applied = applyDuring(args)
            val tenantId = policyAccountId(applied.tenantId)
            val deleted = accountQueries.transactionWithResult {
                lockPolicyAccount(accountQueries, tenantId)
                val policy = metadataPolicyQueries.findById(applied.id).executeAsOneOrNull()
                if (policy == null || policy.account_id != tenantId) {
                    throw com.sphereon.openid.fed.core.error.FederationErrorException(MetadataPolicyNotFoundError(applied.id))
                }
                val row = metadataPolicyQueries.delete(applied.id).executeAsOneOrNull()
                    ?: throw com.sphereon.openid.fed.core.error.FederationErrorException(MetadataPolicyNotFoundError(applied.id))
                advanceLegacyPolicyRevision(revisionQueries, tenantId, policy.key)
                row.toDTO()
            }
            IdkResult.ok(deleted)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (refused: com.sphereon.openid.fed.core.error.FederationErrorException) {
            federationErr(refused.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Failed to delete metadata policy", failure.message, failure))
        }
    }
}

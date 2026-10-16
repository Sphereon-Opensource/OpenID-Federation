package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** One account-locked transaction. No network/KMS work, caller hash, or cross-database transaction. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<AtomicReplaceMetadataPolicyCommand>())
class AtomicReplaceMetadataPolicyCommandImpl internal constructor(
    execution: SessionExecution,
    private val accounts: AccountQueries,
    private val policies: MetadataPolicyQueries,
    private val revisions: AccountMetadataPolicyRevisionQueries,
) : TypedServiceCommandAdapter<AtomicReplaceMetadataPolicyArgs, AccountMetadataPolicySnapshot, FederationError>(
    commandId = AtomicReplaceMetadataPolicyCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<AtomicReplaceMetadataPolicyArgs>(), outputTypeToken = typeToken<AccountMetadataPolicySnapshot>(),
), AtomicReplaceMetadataPolicyCommand {
    @Inject constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries,
        Persistence.metadataPolicyQueries, Persistence.accountMetadataPolicyRevisionQueries)

    override suspend fun doExecute(args: AtomicReplaceMetadataPolicyArgs, applyDuring: (AtomicReplaceMetadataPolicyArgs) -> AtomicReplaceMetadataPolicyArgs): IdkResult<AccountMetadataPolicySnapshot, FederationError> = try {
        // Server serialization captures the exact request. Stored JSON equality ignores map ordering only.
        val request = Json.encodeToString(AtomicReplaceMetadataPolicyArgs.serializer(), applyDuring(args))
        val input = Json.decodeFromString(AtomicReplaceMetadataPolicyArgs.serializer(), request)
        val accountId = policyAccountId(input.accountId)
        if (input.expectedRevision < 0L || input.expectedRevision == Long.MAX_VALUE || input.operationGeneration <= 0L ||
            input.operationToken.isBlank() || input.operationToken.length > 256) policyRefusal("Invalid metadata policy operation coordinates")
        validateRolePolicy(input.entityType, input.policy)
        val result = accounts.transactionWithResult {
            val account = lockPolicyAccount(accounts, accountId, input.expectedEntityIdentifier)
            val old = revisions.findByAccountIdAndEntityType(accountId, input.entityType).executeAsOneOrNull()
            val current = policyState(account, input.entityType, policies, revisions)
            val generation = old?.last_operation_generation
            if (old != null && generation != null && input.operationGeneration <= generation) {
                val retainedRequest = old.last_request_snapshot
                val latestRetry = input.operationGeneration == generation && old.last_operation_token == input.operationToken &&
                    retainedRequest != null && Json.parseToJsonElement(retainedRequest) == Json.parseToJsonElement(request) &&
                    current.revision == input.expectedRevision + 1 && current.policy == input.policy
                if (!latestRetry) policyRefusal("Metadata policy operation is stale or differs from the latest committed operation")
                return@transactionWithResult current
            }
            if (current.revision != input.expectedRevision || current.policy != input.expectedPolicy) {
                policyRefusal("Metadata policy revision or reviewed policy changed")
            }
            for (row in policies.findByAccountIdAndKey(accountId, input.entityType).executeAsList()) {
                val deleted = policies.delete(row.id).executeAsOneOrNull()
                if (deleted == null || deleted.account_id != accountId || deleted.key != input.entityType) {
                    policyRefusal("Metadata policy deletion returned inconsistent ownership")
                }
            }
            input.policy?.let { policy ->
                val created = policies.create(accountId, input.entityType, policy.toString()).executeAsOne()
                if (created.account_id != accountId || created.key != input.entityType) policyRefusal("Metadata policy insertion returned inconsistent ownership")
            }
            val next = current.revision + 1
            val saved = revisions.upsert(accountId, input.entityType, next, input.operationGeneration,
                input.operationToken, request).executeAsOne()
            if (saved.revision != next || saved.last_operation_generation != input.operationGeneration ||
                saved.last_operation_token != input.operationToken || saved.last_request_snapshot != request) {
                policyRefusal("Metadata policy operation fence was not recorded exactly")
            }
            val completed = policyState(account, input.entityType, policies, revisions)
            if (completed.policy != input.policy || completed.revision != next) policyRefusal("Metadata policy result differs from the committed request")
            completed
        }
        IdkResult.ok(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: FederationErrorException) {
        federationErr(refused.federationError)
    } catch (failure: Exception) {
        federationErr(ServerError("Atomic metadata policy replacement failed", failure.message, failure))
    }
}

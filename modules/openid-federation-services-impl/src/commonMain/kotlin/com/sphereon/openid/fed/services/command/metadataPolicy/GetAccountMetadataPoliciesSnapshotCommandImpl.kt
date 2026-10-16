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
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAccountMetadataPoliciesSnapshotCommand>())
class GetAccountMetadataPoliciesSnapshotCommandImpl internal constructor(
    execution: SessionExecution,
    private val accounts: AccountQueries,
    private val policies: MetadataPolicyQueries,
    private val revisions: AccountMetadataPolicyRevisionQueries,
) : TypedServiceCommandAdapter<GetAccountMetadataPoliciesSnapshotArgs, AccountMetadataPoliciesSnapshot, FederationError>(
    commandId = GetAccountMetadataPoliciesSnapshotCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetAccountMetadataPoliciesSnapshotArgs>(), outputTypeToken = typeToken<AccountMetadataPoliciesSnapshot>(),
), GetAccountMetadataPoliciesSnapshotCommand {
    @Inject constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries,
        Persistence.metadataPolicyQueries, Persistence.accountMetadataPolicyRevisionQueries)

    override suspend fun doExecute(args: GetAccountMetadataPoliciesSnapshotArgs, applyDuring: (GetAccountMetadataPoliciesSnapshotArgs) -> GetAccountMetadataPoliciesSnapshotArgs): IdkResult<AccountMetadataPoliciesSnapshot, FederationError> = try {
        val input = applyDuring(args)
        val accountId = policyAccountId(input.accountId)
        IdkResult.ok(captureAccountMetadataPolicies(accounts, policies, revisions, accountId, input.expectedEntityIdentifier).snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: FederationErrorException) {
        federationErr(refused.federationError)
    } catch (failure: Exception) {
        federationErr(ServerError("Metadata policy snapshot failed", failure.message, failure))
    }
}

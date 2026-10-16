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
@ContributesBinding(SessionScope::class, binding = binding<GetAccountMetadataPolicySnapshotCommand>())
class GetAccountMetadataPolicySnapshotCommandImpl internal constructor(
    execution: SessionExecution,
    private val accounts: AccountQueries,
    private val policies: MetadataPolicyQueries,
    private val revisions: AccountMetadataPolicyRevisionQueries,
) : TypedServiceCommandAdapter<GetAccountMetadataPolicySnapshotArgs, AccountMetadataPolicySnapshot, FederationError>(
    commandId = GetAccountMetadataPolicySnapshotCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<GetAccountMetadataPolicySnapshotArgs>(), outputTypeToken = typeToken<AccountMetadataPolicySnapshot>(),
), GetAccountMetadataPolicySnapshotCommand {
    @Inject constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries,
        Persistence.metadataPolicyQueries, Persistence.accountMetadataPolicyRevisionQueries)

    override suspend fun doExecute(args: GetAccountMetadataPolicySnapshotArgs, applyDuring: (GetAccountMetadataPolicySnapshotArgs) -> GetAccountMetadataPolicySnapshotArgs): IdkResult<AccountMetadataPolicySnapshot, FederationError> = try {
        val input = applyDuring(args)
        val accountId = policyAccountId(input.accountId)
        validateRolePolicy(input.entityType, null)
        IdkResult.ok(accounts.transactionWithResult {
            val account = lockPolicyAccount(accounts, accountId, input.expectedEntityIdentifier)
            policyState(account, input.entityType, policies, revisions)
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: FederationErrorException) {
        federationErr(refused.federationError)
    } catch (failure: Exception) {
        federationErr(ServerError("Metadata policy snapshot failed", failure.message, failure))
    }
}

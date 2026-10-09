package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.core.api.http.query.QueryParamUtils
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.SelectedSigningKeyConflictError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetAccountSigningKeySelectionCommand>())
class SetAccountSigningKeySelectionCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
    private val accountSigningKeyQueries: AccountSigningKeyQueries,
) : TypedServiceCommandAdapter<
    SetAccountSigningKeySelectionArgs,
    AccountSigningKeySelection,
    FederationError,
>(
    commandId = SetAccountSigningKeySelectionCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SetAccountSigningKeySelectionArgs>(),
    outputTypeToken = typeToken<AccountSigningKeySelection>(),
), SetAccountSigningKeySelectionCommand {
    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.jwkQueries,
        Persistence.accountSigningKeyQueries,
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun doExecute(
        args: SetAccountSigningKeySelectionArgs,
        applyDuring: (SetAccountSigningKeySelectionArgs) -> SetAccountSigningKeySelectionArgs,
    ): IdkResult<AccountSigningKeySelection, FederationError> {
        val applied = applyDuring(args)
        val accountId = QueryParamUtils.parseUuid(applied.accountId)?.toString()
            ?: return invalid("Account ID must be a UUID")
        if (applied.expectedEntityIdentifier.isBlank()) return invalid("Expected Entity Identifier must not be blank")
        if (applied.expectedRevision < 0L) return invalid("Expected selection revision must not be negative")
        val selectedKeyId = applied.selectedKeyId?.let { QueryParamUtils.parseUuid(it)?.toString() }
        if (applied.selectedKeyId != null && selectedKeyId == null) {
            return invalid("Selected key ID must be a UUID")
        }

        return try {
            val selection = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(accountId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(accountId))
                val accountIdentifier = account.identifier
                if (accountIdentifier == null || accountIdentifier != applied.expectedEntityIdentifier) {
                    throw FederationErrorException(
                        InvalidRequestError("Account identifier does not match expectedEntityIdentifier")
                    )
                }

                val existing = accountSigningKeyQueries.findByAccountId(account.id).executeAsOneOrNull()
                val currentRevision = existing?.revision ?: 0L
                if (currentRevision != applied.expectedRevision || currentRevision == Long.MAX_VALUE) {
                    throw FederationErrorException(SelectedSigningKeyConflictError(account.id))
                }

                val selectedKey = selectedKeyId?.let { id ->
                    val key = jwkQueries.findById(id).executeAsOneOrNull()
                    if (key == null || key.account_id != account.id || key.revoked_at != null) {
                        throw FederationErrorException(KeyNotFoundError(applied.selectedKeyId!!))
                    }
                    key
                }
                val stored = accountSigningKeyQueries.upsert(
                    account.id,
                    selectedKey?.id,
                    currentRevision + 1L,
                ).executeAsOne()
                check(stored.account_id == account.id && stored.jwk_id == selectedKey?.id &&
                    stored.revision == currentRevision + 1L
                ) { "Persisted selected signing-key binding differs from the requested transition" }
                AccountSigningKeySelection(
                    accountId = stored.account_id,
                    entityIdentifier = accountIdentifier,
                    selectedKeyId = stored.jwk_id,
                    revision = stored.revision,
                )
            }
            IdkResult.ok(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Selected signing-key transition failed", failure.message, failure))
        }
    }

    private fun invalid(reason: String): IdkResult<AccountSigningKeySelection, FederationError> =
        federationErr(InvalidRequestError(reason))
}

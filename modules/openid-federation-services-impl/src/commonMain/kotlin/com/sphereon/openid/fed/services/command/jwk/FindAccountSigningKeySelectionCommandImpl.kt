package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.query.QueryParamUtils
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindAccountSigningKeySelectionCommand>())
class FindAccountSigningKeySelectionCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val accountSigningKeyQueries: AccountSigningKeyQueries,
) : TypedServiceCommandAdapter<
    FindAccountSigningKeySelectionArgs,
    AccountSigningKeySelection,
    FederationError,
>(
    commandId = FindAccountSigningKeySelectionCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindAccountSigningKeySelectionArgs>(),
    outputTypeToken = typeToken<AccountSigningKeySelection>(),
), FindAccountSigningKeySelectionCommand {
    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.accountSigningKeyQueries,
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun doExecute(
        args: FindAccountSigningKeySelectionArgs,
        applyDuring: (FindAccountSigningKeySelectionArgs) -> FindAccountSigningKeySelectionArgs,
    ): IdkResult<AccountSigningKeySelection, FederationError> {
        val applied = applyDuring(args)
        val accountId = QueryParamUtils.parseUuid(applied.accountId)?.toString()
            ?: return federationErr(InvalidRequestError("Account ID must be a UUID"))
        if (applied.expectedEntityIdentifier.isBlank()) {
            return federationErr(InvalidRequestError("Expected Entity Identifier must not be blank"))
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

                val stored = accountSigningKeyQueries.findByAccountId(account.id).executeAsOneOrNull()
                AccountSigningKeySelection(
                    accountId = account.id,
                    entityIdentifier = accountIdentifier,
                    selectedKeyId = stored?.jwk_id,
                    revision = stored?.revision ?: 0L,
                )
            }
            IdkResult.ok(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Selected signing-key read failed", failure.message, failure))
        }
    }
}

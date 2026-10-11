package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import com.sphereon.openid.fed.services.mappers.toHistoricalKey

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListHistoricalKeysCommand>())
class ListHistoricalKeysCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
) : TypedServiceCommandAdapter<ListHistoricalKeysArgs, List<HistoricalKey>, FederationError>(
    commandId = ListHistoricalKeysCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ListHistoricalKeysArgs>(),
    outputTypeToken = typeToken<List<HistoricalKey>>(),
), ListHistoricalKeysCommand {
    private val logger = execution.federationLogger("ListHistoricalKeysCommand")

    @Inject
    constructor(execution: SessionExecution) : this(execution, Persistence.accountQueries, Persistence.jwkQueries)

    override suspend fun doExecute(
        args: ListHistoricalKeysArgs,
        applyDuring: (ListHistoricalKeysArgs) -> ListHistoricalKeysArgs,
    ): IdkResult<List<HistoricalKey>, FederationError> {
        val applied = applyDuring(args)
        return try {
            val keys = accountQueries.transactionWithResult {
                accountQueries.findById(applied.tenantId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(applied.tenantId))
                jwkQueries.findHistoricalByAccountId(applied.tenantId).executeAsList().map { it.toHistoricalKey() }
            }
            IdkResult.ok(keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to list historical keys", failure)
            federationErr(ServerError("Failed to list historical keys", failure.message, failure))
        }
    }
}

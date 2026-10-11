package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
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
import com.sphereon.openid.fed.core.error.SelectedSigningKeyConflictError
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.services.mappers.toDTO

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RetireKeyCommand>())
class RetireKeyCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
    private val accountSigningKeyQueries: AccountSigningKeyQueries,
) : TypedServiceCommandAdapter<RetireKeyArgs, TenantJwk, FederationError>(
    commandId = RetireKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<RetireKeyArgs>(),
    outputTypeToken = typeToken<TenantJwk>(),
), RetireKeyCommand {
    private val logger = execution.federationLogger("RetireKeyCommand")

    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.jwkQueries,
        Persistence.accountSigningKeyQueries,
    )

    override suspend fun doExecute(
        args: RetireKeyArgs,
        applyDuring: (RetireKeyArgs) -> RetireKeyArgs,
    ): IdkResult<TenantJwk, FederationError> {
        val applied = applyDuring(args)

        return try {
            val updated = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(applied.tenantId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(applied.tenantId))
                val key = jwkQueries.findById(applied.keyId).executeAsOneOrNull()
                    ?: throw FederationErrorException(KeyNotFoundError(applied.keyId))
                if (key.account_id != account.id) {
                    throw FederationErrorException(KeyNotFoundError(applied.keyId))
                }
                val selection = accountSigningKeyQueries.findByAccountId(account.id).executeAsOneOrNull()
                if (selection?.jwk_id == key.id) {
                    throw FederationErrorException(SelectedSigningKeyConflictError(account.id))
                }
                jwkQueries.retire(key.id).executeAsOneOrNull()
                    ?: throw FederationErrorException(InvalidRequestError("Key is not active"))
            }
            IdkResult.ok(updated.toDTO())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to update key: ${applied.keyId}", failure)
            federationErr(ServerError("Failed to update key", failure.message, failure))
        }
    }
}

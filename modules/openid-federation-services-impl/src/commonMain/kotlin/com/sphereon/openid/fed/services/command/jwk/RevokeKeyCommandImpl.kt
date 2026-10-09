package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException

/**
 * Implementation of the RevokeKeyCommand.
 * Revokes a specific key associated with an account.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RevokeKeyCommand>())
class RevokeKeyCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
    private val accountSigningKeyQueries: AccountSigningKeyQueries,
) : TypedServiceCommandAdapter<RevokeKeyArgs, TenantJwk, FederationError>(
    commandId = RevokeKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<RevokeKeyArgs>(),
    outputTypeToken = typeToken<TenantJwk>()
), RevokeKeyCommand {

    private val logger = execution.federationLogger("RevokeKeyCommand")

    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.jwkQueries,
        Persistence.accountSigningKeyQueries,
    )

    override suspend fun doExecute(
        args: RevokeKeyArgs,
        applyDuring: (RevokeKeyArgs) -> RevokeKeyArgs
    ): IdkResult<TenantJwk, FederationError> {
        val (tenantId, keyId, reason) = applyDuring(args)

        logger.info("Attempting to revoke key ID: $keyId for account: $tenantId")
        return try {
            val revokedKey = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(tenantId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(tenantId))
                val existingKey = jwkQueries.findById(keyId).executeAsOneOrNull()
                    ?: throw FederationErrorException(KeyNotFoundError(keyId))
                if (existingKey.account_id != account.id) {
                    throw FederationErrorException(KeyNotFoundError(keyId))
                }

                val revoked = jwkQueries.revoke(reason, existingKey.id).executeAsOne()
                val selection = accountSigningKeyQueries.findByAccountId(account.id).executeAsOneOrNull()
                if (selection?.jwk_id == existingKey.id) {
                    // Emergency revocation remains possible at exhaustion; MAX is terminal for setters.
                    val nextRevision = if (selection.revision == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        selection.revision + 1L
                    }
                    val cleared = accountSigningKeyQueries.upsert(account.id, null, nextRevision).executeAsOne()
                    check(cleared.account_id == account.id && cleared.jwk_id == null &&
                        cleared.revision == nextRevision
                    ) { "Persisted selected signing-key binding was not cleared with the revoked key" }
                }
                revoked
            }
            logger.debug("Revoked key ID: $keyId with reason: ${reason ?: "no reason provided"}")
            logger.info("Successfully revoked key ID: $keyId")
            IdkResult.ok(revokedKey.toDTO())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            logger.error("Failed to revoke key ID: $keyId due to: ${failure.message}", failure)
            federationErr(ServerError("Failed to revoke key", failure.message, failure))
        }
    }
}

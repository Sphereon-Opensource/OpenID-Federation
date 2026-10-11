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
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.services.mappers.toDTO

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<UpdateKeyRevocationCommand>())
class UpdateKeyRevocationCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountQueries: AccountQueries,
    private val jwkQueries: JwkQueries,
) : TypedServiceCommandAdapter<UpdateKeyRevocationArgs, TenantJwk, FederationError>(
    commandId = UpdateKeyRevocationCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<UpdateKeyRevocationArgs>(),
    outputTypeToken = typeToken<TenantJwk>(),
), UpdateKeyRevocationCommand {
    private val logger = execution.federationLogger("UpdateKeyRevocationCommand")

    @Inject
    constructor(execution: SessionExecution) : this(
        execution,
        Persistence.accountQueries,
        Persistence.jwkQueries,
    )

    override suspend fun doExecute(
        args: UpdateKeyRevocationArgs,
        applyDuring: (UpdateKeyRevocationArgs) -> UpdateKeyRevocationArgs,
    ): IdkResult<TenantJwk, FederationError> {
        val applied = applyDuring(args)
        val reason = FederationKeyRevocationReason.parse(applied.reason)
            ?: return federationErr(InvalidRequestError("Invalid key revocation reason token"))
        // Timestamps are compared by the database clock, the same clock that wrote revoked_at and expires_at.
        val revokedAt = applied.revokedAt?.let {
            runCatching { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }.getOrNull()
                ?: return federationErr(InvalidRequestError("Revocation time is out of range"))
        }

        return try {
            val updated = accountQueries.transactionWithResult {
                val account = accountQueries.findActiveForUpdate(applied.tenantId).executeAsOneOrNull()
                    ?: throw FederationErrorException(AccountNotFoundError(applied.tenantId))
                val key = jwkQueries.findById(applied.keyId).executeAsOneOrNull()
                    ?: throw FederationErrorException(KeyNotFoundError(applied.keyId))
                if (key.account_id != account.id) {
                    throw FederationErrorException(KeyNotFoundError(applied.keyId))
                }
                if (key.revoked_at == null) {
                    if (jwkQueries.findActiveById(key.id).executeAsOneOrNull() != null) {
                        throw FederationErrorException(InvalidRequestError("Key must be revoked or expired"))
                    }
                    jwkQueries.revoke(reason.value, key.revoked_reason, key.id).executeAsOne()
                }
                jwkQueries.updateRevocation(reason.value, revokedAt, key.id).executeAsOneOrNull()
                    ?: throw FederationErrorException(InvalidRequestError("Revocation time must not be in the future"))
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

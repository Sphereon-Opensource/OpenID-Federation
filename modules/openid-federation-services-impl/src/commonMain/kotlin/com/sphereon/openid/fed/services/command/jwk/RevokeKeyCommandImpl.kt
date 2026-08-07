package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the RevokeKeyCommand.
 * Revokes a specific key associated with an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RevokeKeyCommand>())
class RevokeKeyCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<RevokeKeyArgs, TenantJwk, FederationError>(
    commandId = RevokeKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<RevokeKeyArgs>(),
    outputTypeToken = typeToken<TenantJwk>()
), RevokeKeyCommand {

    private val logger = execution.federationLogger("RevokeKeyCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: RevokeKeyArgs,
        applyDuring: (RevokeKeyArgs) -> RevokeKeyArgs
    ): IdkResult<TenantJwk, FederationError> {
        val (tenantId, keyId, reason) = applyDuring(args)

        logger.info("Attempting to revoke key ID: $keyId for account: $tenantId")
        logger.debug("Found account with ID: $tenantId")

        val existingKey = jwkQueries.findById(keyId).executeAsOneOrNull()

        if (existingKey == null) {
            logger.error("Key with ID: $keyId not found for account: $tenantId")
            return federationErr(KeyNotFoundError(keyId))
        }

        logger.debug("Found key with ID: $keyId")

        val ownershipResult = ensureKeyOwnership(existingKey, tenantId)
        if (ownershipResult.isErr) {
            return ownershipResult.error.asErrorResult()
        }

        return try {
            val revokedKey = jwkQueries.revoke(reason, keyId).executeAsOne()
            logger.debug("Revoked key ID: $keyId with reason: ${reason ?: "no reason provided"}")
            logger.info("Successfully revoked key ID: $keyId")
            IdkResult.ok(revokedKey.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to revoke key ID: $keyId due to: ${e.message}", e)
            federationErr(ServerError("Failed to revoke key", e.message, e))
        }
    }

    private fun ensureKeyOwnership(jwk: Jwk, tenantId: String): IdkResult<Unit, FederationError> {
        if (jwk.account_id != tenantId) {
            logger.error("Key does not belong to account: $tenantId")
            return federationErr(KeyNotFoundError(jwk.id))
        }
        return IdkResult.ok(Unit)
    }
}

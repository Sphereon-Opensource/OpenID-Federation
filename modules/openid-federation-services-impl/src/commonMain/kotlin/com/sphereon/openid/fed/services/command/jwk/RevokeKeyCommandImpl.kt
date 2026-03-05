package com.sphereon.openid.fed.services.command.jwk

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
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the RevokeKeyCommand.
 * Revokes a specific key associated with an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = RevokeKeyCommand::class)
class RevokeKeyCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<RevokeKeyArgs, AccountJwk>(
    commandId = RevokeKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<RevokeKeyArgs>(),
    outputTypeToken = typeToken<AccountJwk>()
), RevokeKeyCommand {

    private val logger = execution.federationLogger("RevokeKeyCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: RevokeKeyArgs,
        applyDuring: (RevokeKeyArgs) -> RevokeKeyArgs
    ): IdkResult<AccountJwk, IdkError> {
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

    private fun ensureKeyOwnership(jwk: Jwk, tenantId: String): IdkResult<Unit, IdkError> {
        if (jwk.account_id != tenantId) {
            logger.error("Key does not belong to account: $tenantId")
            return federationErr(KeyNotFoundError(jwk.id))
        }
        return IdkResult.ok(Unit)
    }
}

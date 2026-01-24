package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetKeysCommand.
 * Retrieves keys associated with an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetKeysCommand::class)
class GetKeysCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<GetKeysArgs, Array<AccountJwk>, FederationError>(
    id = GetKeysCommand.COMMAND_ID,
    execution = execution
), GetKeysCommand {

    private val logger = Log.app().withTag("GetKeysCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun getKeys(account: Account, includeRevoked: Boolean): IdkResult<Array<AccountJwk>, FederationError> {
        return execute(GetKeysArgs(account, includeRevoked), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: GetKeysArgs,
        sessionContext: SessionContext,
        applyDuring: (GetKeysArgs) -> GetKeysArgs
    ): IdkResult<Array<AccountJwk>, FederationError> {
        val (account, includeRevoked) = applyDuring(args)

        logger.debug("Retrieving keys for account: ${account.username}")

        return try {
            val keys = jwkQueries.findByAccountId(account.id)
                .executeAsList()
                .filter { includeRevoked || it.revoked_at == null }
                .map { it.toDTO() }
                .toTypedArray()
            logger.debug("Found ${keys.size} keys for account ID: ${account.id}, including revoked keys: $includeRevoked")
            IdkResult.ok(keys)
        } catch (e: Exception) {
            logger.error("Failed to retrieve keys for account: ${account.username}", e)
            IdkResult.err(ServerError("Failed to retrieve keys", e.message, e))
        }
    }
}

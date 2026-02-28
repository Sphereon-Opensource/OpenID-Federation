package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
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
) : TypedServiceCommandAdapter<GetKeysArgs, Array<AccountJwk>>(
    commandId = GetKeysCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetKeysArgs>(),
    outputTypeToken = typeToken<Array<AccountJwk>>()
), GetKeysCommand {

    private val logger = Log.app().withTag("GetKeysCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: GetKeysArgs,
        applyDuring: (GetKeysArgs) -> GetKeysArgs
    ): IdkResult<Array<AccountJwk>, IdkError> {
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
            federationErr(ServerError("Failed to retrieve keys", e.message, e))
        }
    }
}

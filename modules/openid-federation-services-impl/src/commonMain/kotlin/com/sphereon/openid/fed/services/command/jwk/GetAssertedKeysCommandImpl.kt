package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetAssertedKeysCommand.
 * Retrieves keys for an account with assertion that keys exist.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetAssertedKeysCommand::class)
class GetAssertedKeysCommandImpl(
    execution: SessionExecution,
    private val getKeysCommand: GetKeysCommand
) : ExecutionScopedCommandAdapter<GetAssertedKeysArgs, Array<AccountJwk>, FederationError>(
    id = GetAssertedKeysCommand.COMMAND_ID,
    execution = execution
), GetAssertedKeysCommand {

    private val logger = Log.app().withTag("GetAssertedKeysCommand")

    override suspend fun getAssertedKeysForAccount(
        account: Account,
        includeRevoked: Boolean,
        kmsKeyRef: String?,
        kid: String?
    ): IdkResult<Array<AccountJwk>, FederationError> {
        return execute(GetAssertedKeysArgs(account, includeRevoked, kmsKeyRef, kid), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: GetAssertedKeysArgs,
        sessionContext: SessionContext,
        applyDuring: (GetAssertedKeysArgs) -> GetAssertedKeysArgs
    ): IdkResult<Array<AccountJwk>, FederationError> {
        val (account, includeRevoked, kmsKeyRef, kid) = applyDuring(args)

        val allKeysResult = getKeysCommand.getKeys(account, includeRevoked)
        if (allKeysResult.isErr) {
            return allKeysResult
        }

        val allKeys = allKeysResult.value
        val keys = allKeys
            .filter { kmsKeyRef == null || it.kmsKeyRef == kmsKeyRef }
            .filter { kid == null || it.kid == kid }
            .toTypedArray()

        logger.debug("Found ${keys.size} keys for account: ${account.username} with filters - key ref: $kmsKeyRef, kid: $kid")

        return if (keys.isEmpty()) {
            logger.error("No keys found for account: ${account.username}")
            IdkResult.err(KeyNotFoundError("account:${account.id}"))
        } else {
            IdkResult.ok(keys)
        }
    }
}

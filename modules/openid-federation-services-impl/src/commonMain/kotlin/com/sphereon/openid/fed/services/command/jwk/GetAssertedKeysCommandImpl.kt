package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.AccountJwk
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the GetAssertedKeysCommand.
 * Retrieves keys for an account with assertion that keys exist.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetAssertedKeysCommand>())
class GetAssertedKeysCommandImpl(
    execution: SessionExecution,
    private val getKeysCommand: GetKeysCommand
) : TypedServiceCommandAdapter<GetAssertedKeysArgs, Array<AccountJwk>, FederationError>(
    commandId = GetAssertedKeysCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetAssertedKeysArgs>(),
    outputTypeToken = typeToken<Array<AccountJwk>>()
), GetAssertedKeysCommand {

    private val logger = execution.federationLogger("GetAssertedKeysCommand")

    override suspend fun doExecute(
        args: GetAssertedKeysArgs,
        applyDuring: (GetAssertedKeysArgs) -> GetAssertedKeysArgs
    ): IdkResult<Array<AccountJwk>, FederationError> {
        val (tenantId, includeRevoked, kmsKeyRef, kid) = applyDuring(args)

        val allKeysResult = getKeysCommand.execute(GetKeysArgs(tenantId, includeRevoked))
        if (allKeysResult.isErr) {
            return allKeysResult
        }

        val allKeys = allKeysResult.value
        val keys = allKeys
            .filter { kmsKeyRef == null || it.kmsKeyRef == kmsKeyRef }
            .filter { kid == null || it.kid == kid }
            .toTypedArray()

        logger.debug("Found ${keys.size} keys for account: ${tenantId} with filters - key ref: $kmsKeyRef, kid: $kid")

        return if (keys.isEmpty()) {
            logger.error("No keys found for account: ${tenantId}")
            federationErr(KeyNotFoundError("account:${tenantId}"))
        } else {
            IdkResult.ok(keys)
        }
    }
}

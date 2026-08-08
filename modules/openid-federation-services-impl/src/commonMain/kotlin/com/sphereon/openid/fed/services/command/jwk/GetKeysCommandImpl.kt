package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the GetKeysCommand.
 * Retrieves keys associated with an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetKeysCommand>())
class GetKeysCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<GetKeysArgs, Array<TenantJwk>, FederationError>(
    commandId = GetKeysCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetKeysArgs>(),
    outputTypeToken = typeToken<Array<TenantJwk>>()
), GetKeysCommand {

    private val logger = execution.federationLogger("GetKeysCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: GetKeysArgs,
        applyDuring: (GetKeysArgs) -> GetKeysArgs
    ): IdkResult<Array<TenantJwk>, FederationError> {
        val (tenantId, includeRevoked) = applyDuring(args)

        logger.debug("Retrieving keys for account: ${tenantId}")

        return try {
            val records = if (includeRevoked) {
                jwkQueries.findAllByAccountId(tenantId).executeAsList()
            } else {
                jwkQueries.findByAccountId(tenantId).executeAsList()
            }
            val keys = records.map { it.toDTO() }.toTypedArray()
            logger.debug("Found ${keys.size} keys for account ID: ${tenantId}, including revoked keys: $includeRevoked")
            IdkResult.ok(keys)
        } catch (e: Exception) {
            logger.error("Failed to retrieve keys for account: ${tenantId}", e)
            federationErr(ServerError("Failed to retrieve keys", e.message, e))
        }
    }
}

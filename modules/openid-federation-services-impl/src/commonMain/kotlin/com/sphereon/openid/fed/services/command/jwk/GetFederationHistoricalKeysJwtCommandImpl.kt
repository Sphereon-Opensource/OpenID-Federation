package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toHistoricalKey
import com.sphereon.openid.fed.services.signPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the GetFederationHistoricalKeysJwtCommand.
 * Generates a JWT representing the historical federation keys.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetFederationHistoricalKeysJwtCommand>())
class GetFederationHistoricalKeysJwtCommandImpl(
    execution: SessionExecution,
    private val getKeysCommand: GetKeysCommand,
    private val tenantContextResolver: TenantContextResolver,
    private val jwtService: JwtService
) : TypedServiceCommandAdapter<GetFederationHistoricalKeysJwtArgs, String, FederationError>(
    commandId = GetFederationHistoricalKeysJwtCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetFederationHistoricalKeysJwtArgs>(),
    outputTypeToken = typeToken<String>()
), GetFederationHistoricalKeysJwtCommand {

    companion object {
        private const val JWT_TYPE = "jwk-set+jwt"
    }

    private val logger = execution.federationLogger("GetFederationHistoricalKeysJwtCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: GetFederationHistoricalKeysJwtArgs,
        applyDuring: (GetFederationHistoricalKeysJwtArgs) -> GetFederationHistoricalKeysJwtArgs
    ): IdkResult<String, FederationError> = withContext(Dispatchers.IO) {
        val (tenantId) = applyDuring(args)

        try {
            val iss = tenantContextResolver.resolveIdentifier(tenantId)
                ?: return@withContext federationErr(TenantNotFoundError(tenantId))

            val historicalKeys = getFederationHistoricalKeys(tenantId)

            val federationKeysResponse = FederationHistoricalKeysResponse(
                iss = iss,
                iat = (System.currentTimeMillis() / 1000).toDouble(),
                propertyKeys = historicalKeys
            )

            val keysResult = getKeysCommand.execute(GetKeysArgs(tenantId, includeRevoked = false))
            if (keysResult.isErr) {
                return@withContext keysResult.error.asErrorResult()
            }
            val keys = keysResult.value

            if (keys.isEmpty()) {
                logger.error("No keys found for account: $tenantId")
                return@withContext federationErr(ServerError("The system is in an invalid state: no keys for account."))
            }

            val key = keys.first()
            val header = JwtHeader(typ = JWT_TYPE, kid = key.kid, alg = key.alg ?: "RS256")
            val jwtResult = jwtService.signPayload(federationKeysResponse, header, key.kid, key.kmsKeyRef, key.kms)

            if (jwtResult.isErr) {
                logger.error("Failed to sign federation historical keys JWT")
                return@withContext jwtResult
            }

            val jwt = jwtResult.value
            logger.trace("Successfully built federation historical keys JWT for tenant: $tenantId")
            logger.debug("JWT: $jwt")
            IdkResult.ok(jwt)
        } catch (e: Exception) {
            logger.error("Failed to generate federation historical keys JWT", e)
            federationErr(ServerError("Failed to generate federation historical keys JWT", e.message, e))
        }
    }

    private fun getFederationHistoricalKeys(tenantId: String): List<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account: $tenantId")
        val records = jwkQueries.findByAccountId(tenantId).executeAsList()
        logger.debug("Found ${records.size} keys for account ID: $tenantId")
        return records.map { it.toHistoricalKey() }
    }
}

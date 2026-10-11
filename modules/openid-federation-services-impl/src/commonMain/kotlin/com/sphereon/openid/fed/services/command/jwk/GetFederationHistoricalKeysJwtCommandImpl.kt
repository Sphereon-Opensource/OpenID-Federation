package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.signPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/** Signs the account's revoked and expired public keys as a historical keys JWT. */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetFederationHistoricalKeysJwtCommand>())
class GetFederationHistoricalKeysJwtCommandImpl(
    execution: SessionExecution,
    private val listHistoricalKeys: ListHistoricalKeysCommand,
    private val tenantContextResolver: TenantContextResolver,
    private val jwtService: JwtService,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
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

    override suspend fun doExecute(
        args: GetFederationHistoricalKeysJwtArgs,
        applyDuring: (GetFederationHistoricalKeysJwtArgs) -> GetFederationHistoricalKeysJwtArgs
    ): IdkResult<String, FederationError> = withContext(Dispatchers.IO) {
        val (tenantId) = applyDuring(args)

        try {
            val iss = tenantContextResolver.resolveIdentifier(tenantId)
                ?: return@withContext federationErr(TenantNotFoundError(tenantId))

            val historicalKeys = listHistoricalKeys.execute(ListHistoricalKeysArgs(tenantId))
            if (historicalKeys.isErr) return@withContext historicalKeys.error.asErrorResult()

            val federationKeysResponse = FederationHistoricalKeysResponse(
                iss = iss,
                iat = (System.currentTimeMillis() / 1000).toDouble(),
                propertyKeys = historicalKeys.value
            )

            val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(tenantId, iss))
            if (resolved.isErr) {
                return@withContext resolved.error.asErrorResult()
            }
            val key = resolved.value
            val header = JwtHeader(typ = JWT_TYPE, kid = key.kid, alg = key.alg)
            val jwtResult = jwtService.signPayload(federationKeysResponse, header, key.kid, key.kmsKeyRef, key.kms)

            if (jwtResult.isErr) {
                logger.error("Failed to sign federation historical keys JWT")
                return@withContext jwtResult
            }

            val jwt = jwtResult.value
            logger.trace("Successfully built federation historical keys JWT for tenant: $tenantId")
            logger.debug("JWT: $jwt")
            IdkResult.ok(jwt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.error("Failed to generate federation historical keys JWT", e)
            federationErr(ServerError("Failed to generate federation historical keys JWT", e.message, e))
        }
    }

}

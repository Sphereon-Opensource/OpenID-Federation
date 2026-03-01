package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.signPayload
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetSignedResolveResponseJwtCommand.
 * Resolves an entity and returns a signed JWT containing the resolve response.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetSignedResolveResponseJwtCommand::class)
class GetSignedResolveResponseJwtCommandImpl(
    execution: SessionExecution,
    private val resolveEntityCommand: ResolveEntityCommand,
    private val jwkService: JwkService,
    private val jwtService: JwtService
) : TypedServiceCommandAdapter<GetSignedResolveResponseJwtArgs, String>(
    commandId = GetSignedResolveResponseJwtCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetSignedResolveResponseJwtArgs>(),
    outputTypeToken = typeToken<String>()
), GetSignedResolveResponseJwtCommand {

    private val logger = Log.app().withTag("GetSignedResolveResponseJwtCommand")

    override suspend fun doExecute(
        args: GetSignedResolveResponseJwtArgs,
        applyDuring: (GetSignedResolveResponseJwtArgs) -> GetSignedResolveResponseJwtArgs
    ): IdkResult<String, IdkError> {
        val (tenantId, sub, trustAnchor, entityTypes) = applyDuring(args)

        logger.info("Getting signed resolve response JWT for subject: $sub")

        // First resolve the entity
        val resolveResult = resolveEntityCommand.execute(ResolveEntityArgs(tenantId, sub, trustAnchor, entityTypes))

        return when {
            resolveResult.isErr -> resolveResult.error.asErrorResult()
            else -> {
                val response = resolveResult.value
                logger.debug("Successfully built resolve response")

                try {
                    val keysResult = jwkService.getKeys(tenantId, includeRevoked = false).toIdkErrorResult()
                    if (keysResult.isErr) {
                        return keysResult.error.asErrorResult()
                    }

                    val keys = keysResult.value
                    if (keys.isEmpty()) {
                        logger.error("No keys found for account: ${tenantId}")
                        return federationErr(KeyNotFoundError(keyId = "account:${tenantId}"))
                    }

                    val key = keys[0]
                    logger.debug("Using key with kid: ${key.kid}")

                    val jwtHeader = JwtHeader(
                        kid = key.kid,
                        alg = key.alg ?: "RS256",
                        typ = "application/resolve-response+jwt"
                    )

                    jwtService.signPayload(response, header = jwtHeader, kid = key.kid, kmsKeyRef = key.kmsKeyRef, kmsProviderId = key.kms).toIdkErrorResult()
                } catch (e: Exception) {
                    logger.error("Failed to sign resolve response JWT", e)
                    federationErr(ServerError("Failed to sign resolve response", e.message, e))
                }
            }
        }
    }
}

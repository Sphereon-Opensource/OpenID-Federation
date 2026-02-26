package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
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
) : ExecutionScopedCommandAdapter<GetSignedResolveResponseJwtArgs, String, FederationError>(
    id = GetSignedResolveResponseJwtCommand.COMMAND_ID,
    execution = execution
), GetSignedResolveResponseJwtCommand {

    private val logger = Log.app().withTag("GetSignedResolveResponseJwtCommand")

    override suspend fun getSignedResolveResponseJwt(
        account: Account,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): IdkResult<String, FederationError> {
        return execute(GetSignedResolveResponseJwtArgs(account, sub, trustAnchor, entityTypes), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: GetSignedResolveResponseJwtArgs,
        sessionContext: SessionContext,
        applyDuring: (GetSignedResolveResponseJwtArgs) -> GetSignedResolveResponseJwtArgs
    ): IdkResult<String, FederationError> {
        val (account, sub, trustAnchor, entityTypes) = applyDuring(args)

        logger.info("Getting signed resolve response JWT for subject: $sub")

        // First resolve the entity
        val resolveResult = resolveEntityCommand.resolveEntity(account, sub, trustAnchor, entityTypes)

        return when {
            resolveResult.isErr -> resolveResult.error.asErrorResult()
            else -> {
                val response = resolveResult.value
                logger.debug("Successfully built resolve response")

                try {
                    val keysResult = jwkService.getKeys(account, includeRevoked = false)
                    if (keysResult.isErr) {
                        return keysResult.error.asErrorResult()
                    }

                    val keys = keysResult.value
                    if (keys.isEmpty()) {
                        logger.error("No keys found for account: ${account.username}")
                        return IdkResult.err(KeyNotFoundError(keyId = "account:${account.id}"))
                    }

                    val key = keys[0]
                    logger.debug("Using key with kid: ${key.kid}")

                    val jwtHeader = JwtHeader(
                        kid = key.kid,
                        alg = key.alg ?: "RS256",
                        typ = "application/resolve-response+jwt"
                    )

                    jwtService.signPayload(response, header = jwtHeader, kid = key.kid, kmsKeyRef = key.kmsKeyRef, kmsProviderId = key.kms)
                } catch (e: Exception) {
                    logger.error("Failed to sign resolve response JWT", e)
                    IdkResult.err(ServerError("Failed to sign resolve response", e.message, e))
                }
            }
        }
    }
}

package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.core.error.TenantNotFoundError
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
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the GetSignedResolveResponseJwtCommand.
 * Resolves an entity and returns a signed JWT containing the resolve response.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetSignedResolveResponseJwtCommand>())
class GetSignedResolveResponseJwtCommandImpl(
    execution: SessionExecution,
    private val resolveEntityCommand: ResolveEntityCommand,
    private val jwtService: JwtService,
    private val tenantContextResolver: TenantContextResolver,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
) : TypedServiceCommandAdapter<GetSignedResolveResponseJwtArgs, String, FederationError>(
    commandId = GetSignedResolveResponseJwtCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<GetSignedResolveResponseJwtArgs>(),
    outputTypeToken = typeToken<String>()
), GetSignedResolveResponseJwtCommand {

    private val logger = execution.federationLogger("GetSignedResolveResponseJwtCommand")

    override suspend fun doExecute(
        args: GetSignedResolveResponseJwtArgs,
        applyDuring: (GetSignedResolveResponseJwtArgs) -> GetSignedResolveResponseJwtArgs
    ): IdkResult<String, FederationError> {
        val (tenantId, sub, trustAnchors, entityTypes) = applyDuring(args)

        logger.info("Getting signed resolve response JWT for subject: $sub")

        // First resolve the entity
        val resolveResult = resolveEntityCommand.execute(
            ResolveEntityArgs(tenantId, sub, trustAnchors, entityTypes)
        )

        return when {
            resolveResult.isErr -> resolveResult.error.asErrorResult()
            else -> {
                val response = resolveResult.value
                logger.debug("Successfully built resolve response")

                try {
                    val identifier = tenantContextResolver.resolveIdentifier(tenantId)
                        ?: return federationErr(TenantNotFoundError(tenantId))
                    val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(tenantId, identifier))
                    if (resolved.isErr) {
                        return resolved.error.asErrorResult()
                    }
                    val key = resolved.value
                    logger.debug("Using key with kid: ${key.kid}")

                    // OIDFed 1.1 §8.3.2 / §15.3: typ is "resolve-response+jwt"
                    // (media type is application/resolve-response+jwt on the HTTP response)
                    val jwtHeader = JwtHeader(
                        kid = key.kid,
                        alg = key.alg,
                        typ = "resolve-response+jwt"
                    )

                    jwtService.signPayload(response, header = jwtHeader, kid = key.kid, kmsKeyRef = key.kmsKeyRef, kmsProviderId = key.kms)
                } catch (e: Exception) {
                    logger.error("Failed to sign resolve response JWT", e)
                    federationErr(ServerError("Failed to sign resolve response", e.message, e))
                }
            }
        }
    }
}

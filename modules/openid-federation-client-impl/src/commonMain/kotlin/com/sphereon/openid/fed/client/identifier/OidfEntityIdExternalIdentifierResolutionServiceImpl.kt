package com.sphereon.openid.fed.client.identifier

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.asOkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.error.IdkErrorType
import com.sphereon.crypto.core.ResolvedKeyInfo
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.resolution.IdentifierMethodDefaults
import com.sphereon.crypto.resolution.IdentifierTypeUtils
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOpts
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOptsOrResult
import com.sphereon.crypto.resolution.extern.ExternalIdentifierResult
import com.sphereon.crypto.resolution.extern.ExternalIdentifierService
import com.sphereon.crypto.resolution.extern.ExternalIdentifierServiceAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, OidfEntityIdExternalIdentifierResolutionService::class, multibinding = false)
@ContributesBinding(SessionScope::class, ExternalIdentifierService::class, multibinding = true)
class OidfEntityIdExternalIdentifierResolutionServiceImpl(
    execution: SessionExecution,
    private val federationClient: Lazy<FederationClient>
) : ExternalIdentifierServiceAdapter<ExternalIdentifierResult.OIDFEntityId>(
    supportedIdentifierMethods = listOf(IdentifierMethodDefaults.ENTITY_ID),
    execution = execution,
    commandId = COMMAND_ID
), OidfEntityIdExternalIdentifierResolutionService {

    private val logger = execution.federationLogger("OidfEntityIdExternalIdentifierResolution")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override suspend fun doExecute(
        args: ExternalIdentifierOptsOrResult,
        applyDuring: (ExternalIdentifierOptsOrResult) -> ExternalIdentifierOptsOrResult
    ): IdkResult<ExternalIdentifierResult.OIDFEntityId, IdkErrorType> {
        val opts = asSupportedOpts(args)
        if (opts.isErr) return opts.error.asErrorResult()
        val entityIdOpts = opts.value as ExternalIdentifierOIDFEntityIdOpts

        val entityIdentifier = entityIdOpts.identifier
        val trustAnchors = entityIdOpts.trustAnchors
            ?: return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "trustAnchors must be provided for OIDF Entity ID resolution"
            ).asErrorResult()

        logger.debug("Resolving OIDF Entity ID: $entityIdentifier")

        val client = federationClient.value

        // 1. Resolve trust chain
        val resolveResult = client.trustChainResolve(
            entityIdentifier = entityIdentifier,
            trustAnchors = trustAnchors.toTypedArray()
        )
        if (resolveResult.isErr) {
            logger.warn("Trust chain resolution failed for $entityIdentifier: ${resolveResult.error.message.defaultMessage}")
            return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "Trust chain resolution failed for $entityIdentifier: ${resolveResult.error.message.defaultMessage}"
            ).asErrorResult()
        }

        val trustChain = resolveResult.value.trustChain
        if (trustChain.isEmpty()) {
            return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "Empty trust chain resolved for $entityIdentifier"
            ).asErrorResult()
        }

        // 2. Verify trust chain
        val verifyResult = client.trustChainVerify(
            trustChain = trustChain.toTypedArray(),
            trustAnchor = null,
            currentTime = null
        )
        val trustEstablished = verifyResult.isOk && verifyResult.value.isValid

        if (!trustEstablished) {
            logger.warn("Trust chain verification failed for $entityIdentifier")
        }

        // 3. Determine which trust anchor was used (last entry in chain)
        val trustAnchorJwt = trustChain.lastOrNull()
        val trustedAnchorId = if (trustAnchorJwt != null) {
            try {
                val decoded = decodeJWTComponents(trustAnchorJwt)
                decoded.payload["iss"]?.toString()?.trim('"') ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        } else "unknown"

        // 4. Extract JWKS from the leaf entity configuration (first in chain)
        val leafEntityConfigJwt = trustChain.first()
        val leafDecoded = decodeJWTComponents(leafEntityConfigJwt)
        val jwksJson = leafDecoded.payload["jwks"]?.jsonObject
            ?: return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "No JWKS found in entity configuration for $entityIdentifier"
            ).asErrorResult()

        val keysJsonArray = jwksJson["keys"]?.toString()
            ?: return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "No keys array found in entity configuration JWKS for $entityIdentifier"
            ).asErrorResult()

        val openApiJwks: Array<Jwk> = json.decodeFromString(keysJsonArray)

        // 5. Convert OpenAPI Jwk models to IDK CryptoJwk (JwkType) and wrap in ResolvedKeyInfo
        val resolvedKeys = openApiJwks.map { jwk ->
            val jwkJson = Json.encodeToString(Jwk.serializer(), jwk)
            val cryptoJwk: CryptoJwk = cryptoJsonSerializer.decodeFromString(CryptoJwk.serializer(), jwkJson)
            ResolvedKeyInfo.fromKey(cryptoJwk)
        }

        if (resolvedKeys.isEmpty()) {
            return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                message = "No keys found in entity configuration for $entityIdentifier"
            ).asErrorResult()
        }

        logger.info("Resolved ${resolvedKeys.size} key(s) for OIDF Entity ID: $entityIdentifier (trust established: $trustEstablished)")

        return ExternalIdentifierResult.OIDFEntityId(
            identifierOpts = entityIdOpts,
            jwks = resolvedKeys.toTypedArray(),
            keyInfo = resolvedKeys.first(),
            trustedAnchors = listOf(trustedAnchorId),
            trustEstablished = trustEstablished,
            jwtPayload = null
        ).asOkResult()
    }

    override suspend fun supports(args: Any): Boolean {
        val externalArgs = args as? ExternalIdentifierOptsOrResult ?: return false
        return externalArgs is ExternalIdentifierOIDFEntityIdOpts ||
            (externalArgs.method == IdentifierMethodDefaults.ENTITY_ID &&
                IdentifierTypeUtils.isOIDFEntityIdIdentifier(externalArgs.identifier))
    }

    override suspend fun isSupportedIdentifier(identifier: Any): Boolean {
        return IdentifierTypeUtils.isOIDFEntityIdIdentifier(identifier)
    }

    override suspend fun resolve(
        opts: ExternalIdentifierOptsOrResult
    ): IdkResult<ExternalIdentifierResult.OIDFEntityId, IdkErrorType> {
        return execute(opts)
    }

    override suspend fun asSupportedOpts(
        opts: ExternalIdentifierOptsOrResult
    ): IdkResult<ExternalIdentifierOpts, IdkErrorType> {
        return if (opts is ExternalIdentifierOIDFEntityIdOpts) {
            opts.asOkResult()
        } else {
            IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR().asErrorResult()
        }
    }

    @ContributesTo(SessionScope::class)
    interface Component {
        val oidfEntityIdExternalIdentifierResolutionService: OidfEntityIdExternalIdentifierResolutionService
    }

    companion object {
        const val COMMAND_ID = "oidf.resolution.entity-id"
    }
}

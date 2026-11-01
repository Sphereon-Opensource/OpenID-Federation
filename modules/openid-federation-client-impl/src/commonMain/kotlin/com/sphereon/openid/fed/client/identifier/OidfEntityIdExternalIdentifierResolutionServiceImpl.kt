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
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<OidfEntityIdExternalIdentifierResolutionService>())
@ContributesIntoSet(SessionScope::class, binding = binding<ExternalIdentifierService>())
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

        // 2. Trust is established by the resolution itself: trustChainResolve returns only a chain it verified
        //    against the Trust Anchor's pinned or published keys. Verifying it again without those keys would fail
        //    for every real chain.
        val trustEstablished = true

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

        // 4. Extract the JWKS: the federation keys of the leaf entity configuration (first in chain), or, for a
        //    requested Entity Type, the protocol keys of its Resolved Metadata under the chain's metadata policies.
        val entityType = entityIdOpts.entityType
        val jwksJson = if (entityType == null) {
            decodeJWTComponents(trustChain.first()).payload["jwks"]?.jsonObject
                ?: return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                    message = "No JWKS found in entity configuration for $entityIdentifier"
                ).asErrorResult()
        } else {
            // Resolve the whole metadata and select the Entity Type, as trust-domain admission does.
            val resolved = MetadataPolicyOperators.resolveFromTrustChainPayloads(trustChain.map { decodeJWTComponents(it).payload })
            if (!resolved.isValid) {
                return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                    message = "The metadata policies of the Trust Chain of $entityIdentifier do not resolve: ${resolved.errors.joinToString("; ")}"
                ).asErrorResult()
            }
            resolved.metadata[entityType]?.jsonObject?.get("jwks")?.jsonObject
                ?: return IdkError.COMMAND_ARG_NOT_SUPPORTED_ERROR(
                    message = "No $entityType jwks found in the Resolved Metadata of $entityIdentifier"
                ).asErrorResult()
        }

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
    interface Graph {
        val oidfEntityIdExternalIdentifierResolutionService: OidfEntityIdExternalIdentifierResolutionService
    }

    companion object {
        const val COMMAND_ID = "oidf.resolution.entity-id"
    }
}

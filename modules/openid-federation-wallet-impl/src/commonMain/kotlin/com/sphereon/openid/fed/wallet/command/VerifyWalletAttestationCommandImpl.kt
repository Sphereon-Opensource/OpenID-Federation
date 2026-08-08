package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.resolution.extern.ExternalIdentifierJwkOpts
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.findKeyInJwks
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.core.error.WalletAttestationInvalidError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.wallet.policy.WalletAttestationProfile
import com.sphereon.openid.fed.wallet.policy.WalletEntityTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Verifies a Wallet Attestation JWT:
 *
 * 1. Claim / `typ` profile ([WalletAttestationProfile])
 * 2. Trust in the Wallet Provider via embedded `trust_chain` header (offline + OOB TA keys
 *    from [FederationContext]) when present, otherwise network federation trust evaluation
 * 3. Cryptographic signature verification against WP federation keys (fail closed)
 *
 * Wallet profile: attestation is outside the Trust Chain; signature keys come from the
 * Wallet Provider's federation Entity Configuration after trust is established.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyWalletAttestationCommand>())
class VerifyWalletAttestationCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand,
    private val verifyOfflineTrustChainCommand: VerifyOfflineTrustChainCommand,
) : ExecutionScopedCommandAdapter<VerifyWalletAttestationArgs, WalletAttestationResult, FederationError>(
    id = VerifyWalletAttestationCommand.COMMAND_ID,
    execution = execution
), VerifyWalletAttestationCommand {

    private val logger = execution.federationLogger("VerifyWalletAttestationCommand")

    override suspend fun verifyWalletAttestation(
        walletAttestationJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long?
    ): IdkResult<WalletAttestationResult, FederationError> {
        return execute(VerifyWalletAttestationArgs(walletAttestationJwt, trustAnchors, currentTime))
    }

    override suspend fun doExecute(
        args: VerifyWalletAttestationArgs,
        applyDuring: (VerifyWalletAttestationArgs) -> VerifyWalletAttestationArgs
    ): IdkResult<WalletAttestationResult, FederationError> {
        val (walletAttestationJwt, trustAnchors, currentTimeArg) = applyDuring(args)
        val currentTime = currentTimeArg ?: getCurrentEpochTimeSeconds()

        logger.debug("Verifying wallet attestation")

        return try {
            val decoded = decodeJWTComponents(walletAttestationJwt)

            // 1. Structural / claim profile (typ, kid, iss, sub, exp, iat)
            val profile = WalletAttestationProfile.validateStructure(decoded, currentTime)
            if (!profile.ok) {
                return IdkResult.err(
                    WalletAttestationInvalidError(
                        walletProviderId = decoded.payload["iss"]?.jsonPrimitive?.content ?: "unknown",
                        reason = profile.reason ?: "Wallet attestation profile validation failed",
                    )
                )
            }

            val kid = decoded.header.kid
            val walletProviderId = decoded.payload["iss"]!!.jsonPrimitive.content
            logger.debug("Wallet Provider identifier: $walletProviderId")

            // 2. Establish trust in Wallet Provider
            val embeddedChain = WalletAttestationProfile.extractTrustChain(decoded)
            val providerTrustResult: EntityTrustResult = if (!embeddedChain.isNullOrEmpty()) {
                logger.debug(
                    "Attestation carries trust_chain header (${embeddedChain.size} statements); " +
                        "attempting offline verification with OOB TA keys"
                )
                val offline = trustViaEmbeddedTrustChain(
                    trustChain = embeddedChain,
                    walletProviderId = walletProviderId,
                    trustAnchors = trustAnchors,
                    currentTime = currentTime,
                )
                if (offline.isErr) {
                    return IdkResult.err(offline.error)
                }
                offline.value
            } else {
                logger.debug("No trust_chain header; evaluating Wallet Provider trust via federation discovery")
                val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                    entityIdentifier = walletProviderId,
                    trustAnchors = trustAnchors,
                    entityTypes = arrayOf(WalletEntityTypes.OPENID_WALLET_PROVIDER),
                    currentTime = currentTime,
                )
                if (trustResult.isErr) {
                    return IdkResult.err(
                        WalletAttestationInvalidError(
                            walletProviderId = walletProviderId,
                            reason = "Wallet Provider not trusted: ${trustResult.error.message.defaultMessage}",
                        )
                    )
                }
                trustResult.value
            }

            // 3. Signature verification against WP federation keys — fail closed
            val providerConfigJwt = providerTrustResult.trustChain.firstOrNull()
                ?: return IdkResult.err(
                    WalletAttestationInvalidError(
                        walletProviderId = walletProviderId,
                        reason = "No Wallet Provider Entity Configuration in trust chain",
                    )
                )

            val providerConfig = decodeJWTComponents(providerConfigJwt)
            val jwksObject = providerConfig.payload["jwks"]?.jsonObject
                ?: return IdkResult.err(
                    WalletAttestationInvalidError(
                        walletProviderId = walletProviderId,
                        reason = "Wallet Provider Entity Configuration missing jwks",
                    )
                )

            val keysElement = jwksObject["keys"]
                ?: return IdkResult.err(
                    WalletAttestationInvalidError(
                        walletProviderId = walletProviderId,
                        reason = "Wallet Provider jwks missing keys array",
                    )
                )

            val keys: Array<Jwk> = try {
                context.json.decodeFromString(keysElement.toString())
            } catch (e: Exception) {
                return IdkResult.err(
                    WalletAttestationInvalidError(
                        walletProviderId = walletProviderId,
                        reason = "Failed to parse Wallet Provider jwks: ${e.message}",
                        exception = e,
                    )
                )
            }

            val signingKey = findKeyInJwks(keys, kid)
                ?: return IdkResult.err(
                    SignatureVerificationFailedError(
                        reason = "Wallet attestation signing key not found in Wallet Provider JWKS",
                        keyId = kid,
                    )
                )

            val jwkJson = Json.encodeToString(Jwk.serializer(), signingKey)
            val cryptoJwk: CryptoJwk = cryptoJsonSerializer.decodeFromString(
                CryptoJwk.serializer(), jwkJson
            )

            val verifyResult = context.jwtService.verifyJws(
                VerifyJwsArgs(
                    jws = JwsCompact(walletAttestationJwt),
                    identifier = ExternalIdentifierJwkOpts(identifier = cryptoJwk),
                )
            )

            if (verifyResult.isErr || !verifyResult.value.isValid) {
                return IdkResult.err(
                    SignatureVerificationFailedError(
                        reason = "Wallet attestation signature verification failed",
                        keyId = kid,
                    )
                )
            }

            logger.debug("Wallet attestation signature verified successfully")
            logger.info("Wallet attestation verified for provider: $walletProviderId")

            IdkResult.ok(
                WalletAttestationResult(
                    valid = true,
                    walletProviderIdentifier = walletProviderId,
                    walletProviderTrustResult = providerTrustResult,
                )
            )
        } catch (e: Exception) {
            logger.error("Wallet attestation verification failed", e)
            IdkResult.err(
                WalletAttestationInvalidError(
                    walletProviderId = "unknown",
                    reason = "Wallet attestation verification failed: ${e.message}",
                    exception = e,
                )
            )
        }
    }

    /**
     * Offline path: verify embedded trust_chain against OOB Trust Anchor keys from context.
     * Tries each [trustAnchors] entry that has configured public keys.
     */
    private suspend fun trustViaEmbeddedTrustChain(
        trustChain: List<String>,
        walletProviderId: String,
        trustAnchors: Array<String>,
        currentTime: Long,
    ): IdkResult<EntityTrustResult, FederationError> {
        val chainArray = trustChain.toTypedArray()

        // Verify leaf EC iss/sub matches expected Wallet Provider when possible
        try {
            val leaf = decodeJWTComponents(trustChain.first())
            val leafSub = leaf.payload["sub"]?.jsonPrimitive?.content
            val leafIss = leaf.payload["iss"]?.jsonPrimitive?.content
            if (leafSub != null && leafSub != walletProviderId && leafIss != walletProviderId) {
                // Leaf may be WP EC (iss==sub==WP) — soft check only when both present and differ
                if (leafIss == leafSub && leafSub != walletProviderId) {
                    return IdkResult.err(
                        WalletAttestationInvalidError(
                            walletProviderId = walletProviderId,
                            reason = "Embedded trust_chain leaf entity '$leafSub' does not match attestation iss '$walletProviderId'",
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Structural issues will surface in offline crypto verify
        }

        val candidates = trustAnchors.toList().ifEmpty {
            context.trustAnchorPublicKeys.keys.toList()
        }

        if (candidates.isEmpty()) {
            return IdkResult.err(
                WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "Attestation trust_chain present but no trustAnchors provided for offline verification",
                )
            )
        }

        var lastError: String? = null
        for (ta in candidates) {
            val oobKeys = context.trustAnchorPublicKeys[ta]
            if (oobKeys.isNullOrEmpty()) {
                lastError = "No out-of-band Trust Anchor keys configured for $ta"
                logger.debug(lastError)
                continue
            }

            val offline = verifyOfflineTrustChainCommand.verifyOfflineTrustChain(
                trustChain = chainArray,
                trustAnchor = ta,
                trustAnchorPublicKeys = oobKeys,
                currentTime = currentTime,
            )
            if (offline.isOk && offline.value.valid) {
                logger.info("Embedded trust_chain verified offline against TA $ta")
                val leafMeta = try {
                    decodeJWTComponents(trustChain.first()).payload["metadata"]?.jsonObject
                } catch (_: Exception) {
                    null
                }
                if (leafMeta != null && leafMeta[WalletEntityTypes.OPENID_WALLET_PROVIDER] == null) {
                    return IdkResult.err(
                        WalletAttestationInvalidError(
                            walletProviderId = walletProviderId,
                            reason = "Embedded trust_chain leaf metadata missing openid_wallet_provider entity type",
                        )
                    )
                }

                return IdkResult.ok(
                    EntityTrustResult(
                        trusted = true,
                        entityIdentifier = walletProviderId,
                        trustChain = trustChain,
                        effectiveMetadata = leafMeta,
                        verifiedTrustMarks = emptyList(),
                        trustAnchor = ta,
                    )
                )
            }
            lastError = if (offline.isErr) {
                offline.error.message.defaultMessage
            } else {
                offline.value.detail ?: "Offline trust chain verification returned invalid for $ta"
            }
            logger.debug("Offline trust_chain verify failed for TA $ta: $lastError")
        }

        return IdkResult.err(
            WalletAttestationInvalidError(
                walletProviderId = walletProviderId,
                reason = "Embedded trust_chain could not be verified offline: ${lastError ?: "no matching TA keys"}",
            )
        )
    }
}

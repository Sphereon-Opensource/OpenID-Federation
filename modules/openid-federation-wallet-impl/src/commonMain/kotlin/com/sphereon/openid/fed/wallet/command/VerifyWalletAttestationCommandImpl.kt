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
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.core.error.WalletAttestationInvalidError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Verifies a Wallet Attestation JWT by establishing federation trust in the Wallet Provider
 * and cryptographically verifying the attestation signature (fail closed).
 *
 * Wallet profile: attestation is outside the Trust Chain; signature keys come from the
 * Wallet Provider's federation Entity Configuration (`jwks`) after trust evaluation.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyWalletAttestationCommand>())
class VerifyWalletAttestationCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand
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
        val (walletAttestationJwt, trustAnchors, currentTime) = applyDuring(args)

        logger.debug("Verifying wallet attestation")

        return try {
            // 1. Decode the wallet attestation JWT
            val decoded = decodeJWTComponents(walletAttestationJwt)
            val kid = decoded.header.kid
            if (kid.isNullOrBlank()) {
                return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = "unknown",
                    reason = "Wallet attestation missing required 'kid' header"
                ))
            }

            // 2. Extract the issuer (Wallet Provider identifier)
            val walletProviderId = decoded.payload["iss"]?.jsonPrimitive?.content
                ?: return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = "unknown",
                    reason = "Wallet attestation missing required 'iss' claim"
                ))

            logger.debug("Wallet Provider identifier: $walletProviderId")

            // 3. Evaluate the Wallet Provider's trust in the federation
            //    (requires openid_wallet_provider entity type in Resolved Metadata)
            val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                entityIdentifier = walletProviderId,
                trustAnchors = trustAnchors,
                entityTypes = arrayOf("openid_wallet_provider"),
                currentTime = currentTime
            )

            if (trustResult.isErr) {
                return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "Wallet Provider not trusted: ${trustResult.error.message.defaultMessage}"
                ))
            }

            val providerTrustResult = trustResult.value

            // 4. Verify attestation signature with Wallet Provider federation keys — fail closed
            val providerConfigJwt = providerTrustResult.trustChain.firstOrNull()
                ?: return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "No Wallet Provider Entity Configuration in trust chain"
                ))

            val providerConfig = decodeJWTComponents(providerConfigJwt)
            val jwksObject = providerConfig.payload["jwks"]?.jsonObject
                ?: return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "Wallet Provider Entity Configuration missing jwks"
                ))

            val keysElement = jwksObject["keys"]
                ?: return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "Wallet Provider jwks missing keys array"
                ))

            val keys: Array<Jwk> = try {
                context.json.decodeFromString(keysElement.toString())
            } catch (e: Exception) {
                return IdkResult.err(WalletAttestationInvalidError(
                    walletProviderId = walletProviderId,
                    reason = "Failed to parse Wallet Provider jwks: ${e.message}",
                    exception = e
                ))
            }

            val signingKey = findKeyInJwks(keys, kid)
                ?: return IdkResult.err(SignatureVerificationFailedError(
                    reason = "Wallet attestation signing key not found in Wallet Provider JWKS",
                    keyId = kid
                ))

            val jwkJson = Json.encodeToString(Jwk.serializer(), signingKey)
            val cryptoJwk: CryptoJwk = cryptoJsonSerializer.decodeFromString(
                CryptoJwk.serializer(), jwkJson
            )

            val verifyResult = context.jwtService.verifyJws(
                VerifyJwsArgs(
                    jws = JwsCompact(walletAttestationJwt),
                    identifier = ExternalIdentifierJwkOpts(identifier = cryptoJwk)
                )
            )

            if (verifyResult.isErr || !verifyResult.value.isValid) {
                return IdkResult.err(SignatureVerificationFailedError(
                    reason = "Wallet attestation signature verification failed",
                    keyId = kid
                ))
            }

            logger.debug("Wallet attestation signature verified successfully")
            logger.info("Wallet attestation verified for provider: $walletProviderId")

            IdkResult.ok(WalletAttestationResult(
                valid = true,
                walletProviderIdentifier = walletProviderId,
                walletProviderTrustResult = providerTrustResult
            ))
        } catch (e: Exception) {
            logger.error("Wallet attestation verification failed", e)
            IdkResult.err(WalletAttestationInvalidError(
                walletProviderId = "unknown",
                reason = "Wallet attestation verification failed: ${e.message}",
                exception = e
            ))
        }
    }
}

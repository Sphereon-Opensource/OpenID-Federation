package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.VerifyJwsArgs
import com.sphereon.crypto.resolution.extern.ExternalIdentifierJwkOpts
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.CredentialIssuerVerificationError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.FederationEntityMetadata
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyCredentialIssuerCommand>())
class VerifyCredentialIssuerCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand
) : ExecutionScopedCommandAdapter<VerifyCredentialIssuerArgs, CredentialIssuerResult, FederationError>(
    id = VerifyCredentialIssuerCommand.COMMAND_ID,
    execution = execution
), VerifyCredentialIssuerCommand {

    private val logger = execution.federationLogger("VerifyCredentialIssuerCommand")

    override suspend fun verifyCredentialIssuer(
        credentialJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long?
    ): IdkResult<CredentialIssuerResult, FederationError> {
        return execute(VerifyCredentialIssuerArgs(credentialJwt, trustAnchors, currentTime))
    }

    override suspend fun doExecute(
        args: VerifyCredentialIssuerArgs,
        applyDuring: (VerifyCredentialIssuerArgs) -> VerifyCredentialIssuerArgs
    ): IdkResult<CredentialIssuerResult, FederationError> {
        val (credentialJwt, trustAnchors, currentTime) = applyDuring(args)

        logger.debug("Verifying credential issuer through federation")

        return try {
            // 1. Decode the credential JWT
            val decoded = decodeJWTComponents(credentialJwt)
            val kid = decoded.header.kid

            // 2. Extract issuer identifier from credential payload
            val issuerResult = FederationEntityMetadata.extractIssuerIdentifier(decoded.payload)
            val issuerIdentifier = issuerResult.identifier
                ?: return IdkResult.err(CredentialIssuerVerificationError(
                    issuerId = "unknown",
                    reason = issuerResult.error ?: "Could not extract issuer identifier from credential"
                ))

            logger.debug("Credential issuer identifier: $issuerIdentifier (source: ${issuerResult.source})")

            // 3. Evaluate the issuer's trust in the federation
            val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                entityIdentifier = issuerIdentifier,
                trustAnchors = trustAnchors,
                entityTypes = arrayOf("openid_credential_issuer"),
                currentTime = currentTime
            )

            if (trustResult.isErr) {
                return IdkResult.err(CredentialIssuerVerificationError(
                    issuerId = issuerIdentifier,
                    reason = "Issuer not trusted: ${trustResult.error.message.defaultMessage}"
                ))
            }

            val entityTrustResult = trustResult.value

            // 4. Extract vc_issuer signing keys from entity configuration metadata
            val entityConfigJwt = entityTrustResult.trustChain.firstOrNull()
                ?: return IdkResult.err(CredentialIssuerVerificationError(
                    issuerId = issuerIdentifier,
                    reason = "No entity configuration in trust chain"
                ))

            val metadata = decodeJWTComponents(entityConfigJwt).payload["metadata"]?.jsonObject
                ?: return IdkResult.err(CredentialIssuerVerificationError(
                    issuerId = issuerIdentifier,
                    reason = "No metadata in entity configuration"
                ))

            // 5. Find matching key in vc_issuer.jwks by kid
            val vcIssuerKeyJson = FederationEntityMetadata.findVcIssuerKey(metadata, kid)
                ?: return IdkResult.err(CredentialIssuerVerificationError(
                    issuerId = issuerIdentifier,
                    reason = "No key with kid '$kid' found in vc_issuer.jwks"
                ))

            // 6. Verify credential signature using IDK identifier resolution
            val cryptoJwk: CryptoJwk = cryptoJsonSerializer.decodeFromString(
                CryptoJwk.serializer(), vcIssuerKeyJson.toString()
            )

            val verifyResult = context.jwtService.verifyJws(
                VerifyJwsArgs(
                    jws = JwsCompact(credentialJwt),
                    identifier = ExternalIdentifierJwkOpts(identifier = cryptoJwk)
                )
            )

            if (verifyResult.isErr || !verifyResult.value.isValid) {
                return IdkResult.err(SignatureVerificationFailedError(
                    reason = "Credential signature verification failed against vc_issuer key",
                    keyId = kid
                ))
            }

            logger.info("Credential issuer $issuerIdentifier verified successfully (kid: $kid)")

            IdkResult.ok(CredentialIssuerResult(
                valid = true,
                issuerIdentifier = issuerIdentifier,
                issuerIdentifierSource = issuerResult.source,
                signingKeyId = kid,
                entityTrustResult = entityTrustResult
            ))
        } catch (e: Exception) {
            logger.error("Credential issuer verification failed", e)
            IdkResult.err(CredentialIssuerVerificationError(
                issuerId = "unknown",
                reason = "Credential issuer verification failed: ${e.message}",
                exception = e
            ))
        }
    }
}

package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.findKeyInJwks
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.trustMarkService.TrustMarkServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.core.error.TrustMarkExpiredError
import com.sphereon.openid.fed.core.error.TrustMarkInvalidError
import com.sphereon.openid.fed.core.error.TrustMarkIssuerNotAuthorizedError
import com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustMarkOwner
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Validates Trust Marks per OpenID Federation 1.1 §7.2–§7.3 under a specific federation Trust Anchor.
 *
 * Cross-federation: if the mark type is not listed in this TA's `trust_mark_issuers` /
 * `trust_mark_owners`, returns [TrustMarkNotRecognizedError] so callers can filter the mark
 * out for this federation without rejecting the subject Entity.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyTrustMarkCommand>())
class VerifyTrustMarkCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand
) : ExecutionScopedCommandAdapter<VerifyTrustMarkArgs, TrustMarkValidationResponse, FederationError>(
    id = VerifyTrustMarkCommand.COMMAND_ID,
    execution = execution
), VerifyTrustMarkCommand {

    private val logger = TrustMarkServiceConst.LOG

    companion object {
        private const val TM_TYP = "trust-mark+jwt"
        private const val DELEGATION_TYP = "trust-mark-delegation+jwt"
        private const val CLOCK_SKEW_SECONDS = 5L
    }

    override suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?,
        subject: String?
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        return execute(
            VerifyTrustMarkArgs(trustMark, trustAnchorConfig, currentTime, subject)
        )
    }

    override suspend fun doExecute(
        args: VerifyTrustMarkArgs,
        applyDuring: (VerifyTrustMarkArgs) -> VerifyTrustMarkArgs
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val (trustMark, trustAnchorConfig, currentTime, subject) = applyDuring(args)

        logger.debug("Starting Trust Mark validation")
        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()
        val trustAnchorId = trustAnchorConfig.sub ?: trustAnchorConfig.iss

        try {
            // --- Structural JWT checks (§7.3 steps 1–3) ---
            val decodedTrustMark = decodeJWTComponents(trustMark)

            val typ = decodedTrustMark.header.typ
            if (typ != null && typ != TM_TYP) {
                // Allow missing typ for legacy marks only if we want — spec says MUST have typ.
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = "unknown",
                    reason = "Trust Mark typ must be '$TM_TYP', got '${typ ?: "(missing)"}'"
                ))
            }
            if (typ == null) {
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = "unknown",
                    reason = "Trust Mark missing required typ header '$TM_TYP'"
                ))
            }

            val alg = decodedTrustMark.header.alg
            if (alg.isBlank() || alg.equals("none", ignoreCase = true)) {
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = "unknown",
                    reason = "Trust Mark alg must be a signing algorithm and MUST NOT be 'none'"
                ))
            }

            val trustMarkId = decodedTrustMark.payload["trust_mark_type"]?.jsonPrimitive?.contentOrNull
                ?: return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = "unknown",
                    reason = "Trust Mark missing required 'trust_mark_type' claim"
                ))

            val trustMarkIssuer = decodedTrustMark.payload["iss"]?.jsonPrimitive?.contentOrNull
                ?: return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark missing required 'iss' claim"
                ))

            val trustMarkSub = decodedTrustMark.payload["sub"]?.jsonPrimitive?.contentOrNull
                ?: return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark missing required 'sub' claim"
                ))

            // --- Federation recognition (cross-federation filter) ---
            val recognition = TrustMarkFederationPolicy.recognize(trustMarkId, trustAnchorConfig)
            if (recognition == TrustMarkRecognition.NOT_RECOGNIZED) {
                logger.debug(
                    "Trust Mark type $trustMarkId not recognized by federation TA $trustAnchorId — filter out"
                )
                return IdkResult.err(TrustMarkNotRecognizedError(
                    trustMarkId = trustMarkId,
                    trustAnchorId = trustAnchorId,
                    reason = "Trust Mark type is not listed in this Trust Anchor's " +
                        "trust_mark_issuers or trust_mark_owners"
                ))
            }

            // --- §7.3 step 4: sub matches subject Entity (when known) ---
            if (subject != null && trustMarkSub != subject) {
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark sub '$trustMarkSub' does not match subject Entity '$subject'"
                ))
            }

            // --- §7.3 step 5: iat ---
            val iat = decodedTrustMark.payload["iat"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
            if (iat == null || iat > timeToUse + CLOCK_SKEW_SECONDS) {
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark has invalid or future iat"
                ))
            }

            // --- §7.3 step 6: exp is OPTIONAL; if present must be in the future ---
            val expElement = decodedTrustMark.payload["exp"]
            if (expElement != null) {
                val exp = expElement.jsonPrimitive.content.toDoubleOrNull()?.toLong()
                if (exp == null || exp <= timeToUse - CLOCK_SKEW_SECONDS) {
                    logger.error("Trust Mark has expired")
                    return IdkResult.err(TrustMarkExpiredError(trustMarkId = trustMarkId))
                }
            }

            // --- Issuer authorization under this federation ---
            if (!TrustMarkFederationPolicy.isIssuerAuthorized(
                    recognition, trustMarkId, trustMarkIssuer, trustAnchorConfig
                )
            ) {
                return IdkResult.err(TrustMarkIssuerNotAuthorizedError(trustMarkId, trustMarkIssuer))
            }

            // --- Establish trust in TM Issuer (§7.3 preamble / §10) when chain is expected ---
            val issuerTrust = establishIssuerTrust(
                trustMarkIssuer = trustMarkIssuer,
                trustAnchorId = trustAnchorId,
                recognition = recognition,
                timeToUse = timeToUse
            )
            if (issuerTrust.isErr) {
                return issuerTrust.map { TrustMarkValidationResponse(false) }
            }

            // --- §7.3 step 7: signature with issuer federation keys ---
            val issuerConfig = issuerTrust.value
            val signingKey = issuerConfig.jwks.propertyKeys?.find { it.kid == decodedTrustMark.header.kid }
                ?: return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark signing key not found in issuer's JWKS"
                ))

            if (!context.jwtService.verifyJwtSignature(trustMark, signingKey)) {
                logger.error("Trust Mark signature verification failed")
                return IdkResult.err(SignatureVerificationFailedError(
                    reason = "Trust Mark signature verification failed",
                    keyId = decodedTrustMark.header.kid
                ))
            }
            logger.debug("Trust Mark signature verified successfully")

            // --- §7.3 steps 8–9: owners require delegation; any delegation must validate ---
            val hasDelegation = decodedTrustMark.payload["delegation"] != null
            if (recognition == TrustMarkRecognition.OWNER_DELEGATION) {
                if (!hasDelegation) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = trustMarkId,
                        reason = "Trust Mark type is owned; delegation claim is required"
                    ))
                }
            }

            if (hasDelegation) {
                val owners = trustAnchorConfig.trustMarkOwners
                val owner = owners?.get(trustMarkId)
                    ?: return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = trustMarkId,
                        reason = "Trust Mark has delegation but type is not in trust_mark_owners"
                    ))
                val delegationResult = validateDelegation(
                    trustMarkId = trustMarkId,
                    trustMarkIssuer = trustMarkIssuer,
                    decodedTrustMark = decodedTrustMark,
                    owner = owner,
                    timeToUse = timeToUse
                )
                if (delegationResult.isErr) {
                    return delegationResult
                }
            }

            return IdkResult.ok(TrustMarkValidationResponse(true))
        } catch (e: Exception) {
            logger.error("Trust Mark validation failed", e)
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = "unknown",
                reason = "Trust Mark validation failed: ${e.message}",
                exception = e
            ))
        }
    }

    /**
     * Trust Mark Issuer must be trustworthy under this federation.
     * - Issuer == Trust Anchor: self Entity Configuration is enough.
     * - Authorized issuers: resolve + verify Trust Chain to the TA.
     * - Anyone-may-issue: only require fetchable issuer Entity Configuration (external issuers).
     */
    private suspend fun establishIssuerTrust(
        trustMarkIssuer: String,
        trustAnchorId: String,
        recognition: TrustMarkRecognition,
        timeToUse: Long
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        val issuerConfigResult = getEntityConfigurationCommand.getEntityConfiguration(trustMarkIssuer)
        if (issuerConfigResult.isErr) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = "unknown",
                reason = "Failed to fetch Trust Mark issuer configuration: " +
                    issuerConfigResult.error.message.defaultMessage
            ))
        }
        val issuerConfig = issuerConfigResult.value

        if (trustMarkIssuer == trustAnchorId) {
            return IdkResult.ok(issuerConfig)
        }

        when (recognition) {
            TrustMarkRecognition.ANYONE_MAY_ISSUE -> {
                // External / open issuance: EC fetch + later signature is sufficient
                return IdkResult.ok(issuerConfig)
            }
            TrustMarkRecognition.AUTHORIZED_ISSUERS,
            TrustMarkRecognition.OWNER_DELEGATION -> {
                val chainResult = resolveTrustChainCommand.resolveTrustChain(
                    entityIdentifier = trustMarkIssuer,
                    trustAnchors = arrayOf(trustAnchorId),
                    maxDepth = 5
                )
                if (chainResult.isErr) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = "unknown",
                        reason = "Could not establish Trust Chain from Trust Mark issuer " +
                            "'$trustMarkIssuer' to Trust Anchor '$trustAnchorId': " +
                            chainResult.error.message.defaultMessage
                    ))
                }
                val chain = chainResult.value.trustChain
                if (chain.isEmpty()) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = "unknown",
                        reason = "Empty Trust Chain for Trust Mark issuer '$trustMarkIssuer'"
                    ))
                }
                val verifyResult = verifyTrustChainCommand.verifyTrustChain(
                    trustChain = chain.toTypedArray(),
                    trustAnchor = trustAnchorId,
                    currentTime = timeToUse
                )
                if (verifyResult.isErr || !verifyResult.value.isValid) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = "unknown",
                        reason = "Trust Chain verification failed for Trust Mark issuer '$trustMarkIssuer'"
                    ))
                }
                return IdkResult.ok(issuerConfig)
            }
            TrustMarkRecognition.NOT_RECOGNIZED -> {
                return IdkResult.err(TrustMarkNotRecognizedError(
                    trustMarkId = "unknown",
                    trustAnchorId = trustAnchorId
                ))
            }
        }
    }

    private suspend fun validateDelegation(
        trustMarkId: String,
        trustMarkIssuer: String,
        decodedTrustMark: Jwt,
        owner: TrustMarkOwner,
        timeToUse: Long
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val delegationJwt = decodedTrustMark.payload["delegation"]?.jsonPrimitive?.contentOrNull
            ?: return IdkResult.err(TrustMarkInvalidError(trustMarkId, "Trust Mark missing required delegation claim"))

        val decodedDelegation = try {
            decodeJWTComponents(delegationJwt)
        } catch (e: Exception) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Invalid delegation JWT: ${e.message}",
                exception = e
            ))
        }

        // §7.2.2 steps 2–3: typ and alg
        val delTyp = decodedDelegation.header.typ
        if (delTyp != DELEGATION_TYP) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation typ must be '$DELEGATION_TYP', got '${delTyp ?: "(missing)"}'"
            ))
        }
        val delAlg = decodedDelegation.header.alg
        if (delAlg.isBlank() || delAlg.equals("none", ignoreCase = true)) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation alg MUST NOT be 'none'"
            ))
        }

        // §7.2.2 step 4: sub = Trust Mark Issuer
        val delSub = decodedDelegation.payload["sub"]?.jsonPrimitive?.contentOrNull
        if (delSub != trustMarkIssuer) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation sub must match Trust Mark issuer"
            ))
        }

        // §7.2.2 step 5: iss = owner
        val ownerSub = owner.sub
            ?: return IdkResult.err(TrustMarkInvalidError(trustMarkId, "Trust Mark owner missing sub claim"))
        val delIss = decodedDelegation.payload["iss"]?.jsonPrimitive?.contentOrNull
        if (delIss != ownerSub) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation issuer does not match Trust Mark owner"
            ))
        }

        // §7.2.2 steps 6–7: iat / optional exp
        val delIat = decodedDelegation.payload["iat"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
        if (delIat == null || delIat > timeToUse + CLOCK_SKEW_SECONDS) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation has invalid or future iat"
            ))
        }
        val delExpEl = decodedDelegation.payload["exp"]
        if (delExpEl != null) {
            val delExp = delExpEl.jsonPrimitive.content.toDoubleOrNull()?.toLong()
            if (delExp == null || delExp <= timeToUse - CLOCK_SKEW_SECONDS) {
                return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Delegation has expired"
                ))
            }
        }

        // §7.2.2 step 8: trust_mark_type match
        val delType = decodedDelegation.payload["trust_mark_type"]?.jsonPrimitive?.contentOrNull
        if (delType != null && delType != trustMarkId) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation trust_mark_type does not match Trust Mark"
            ))
        }

        // §7.2.2 step 9: signature with owner keys
        val ownerKeys: Array<Jwk> = owner.jwks?.toTypedArray()
            ?: return IdkResult.err(TrustMarkInvalidError(trustMarkId, "No JWKS found for Trust Mark owner"))

        val delegationKey = findKeyInJwks(ownerKeys, decodedDelegation.header.kid)
            ?: return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation signing key not found in owner's JWKS"
            ))

        if (!context.jwtService.verifyJwtSignature(delegationJwt, delegationKey)) {
            return IdkResult.err(SignatureVerificationFailedError(
                reason = "Delegation signature verification failed",
                keyId = decodedDelegation.header.kid
            ))
        }

        return IdkResult.ok(TrustMarkValidationResponse(true))
    }
}

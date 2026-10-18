package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.findKeyInJwks
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.SignatureVerificationFailedError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.core.error.TrustMarkInvalidError
import com.sphereon.openid.fed.core.error.TrustMarkIssuerNotAuthorizedError
import com.sphereon.openid.fed.core.error.TrustMarkNotRecognizedError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.TrustMarkOwner
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlin.coroutines.cancellation.CancellationException
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

    private val logger = execution.federationLogger("VerifyTrustMarkCommand")

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

            TrustMarkValidation.headerError(decodedTrustMark)?.let { return IdkResult.err(it) }

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

            decodedTrustMark.payload["sub"]?.jsonPrimitive?.contentOrNull
                ?: return IdkResult.err(TrustMarkInvalidError(
                    trustMarkId = trustMarkId,
                    reason = "Trust Mark missing required 'sub' claim"
                ))

            // --- Federation recognition (cross-federation filter) ---
            val recognition = TrustMarkFederationPolicy.recognize(trustMarkId, trustAnchorConfig)
            if (recognition == TrustMarkRecognition.NOT_RECOGNIZED) {
                logger.debug(
                    "Trust Mark type $trustMarkId not recognized by federation TA $trustAnchorId; filter out"
                )
                return IdkResult.err(TrustMarkNotRecognizedError(
                    trustMarkId = trustMarkId,
                    trustAnchorId = trustAnchorId,
                    reason = "Trust Mark type is not listed in this Trust Anchor's " +
                        "trust_mark_issuers or trust_mark_owners"
                ))
            }

            // --- §7.3 step 4: sub matches subject Entity (when known) ---
            TrustMarkValidation.subjectError(decodedTrustMark, trustMarkId, subject)?.let { return IdkResult.err(it) }

            // --- §7.3 step 5: iat ---
            TrustMarkValidation.timeError(decodedTrustMark, trustMarkId, timeToUse)?.let { return IdkResult.err(it) }

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
                trustAnchorConfig = trustAnchorConfig,
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

            advertisedActivityError(trustMark, trustMarkId, trustMarkIssuer, issuerConfig, timeToUse)?.let {
                return IdkResult.err(it)
            }
            return IdkResult.ok(TrustMarkValidationResponse(true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.error("Trust Mark validation failed", e)
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = "unknown",
                reason = "Trust Mark validation failed: ${e.message}",
                exception = e
            ))
        }
    }

    /** An advertised authoritative status answer must be active; absence preserves signature/time validation. */
    private suspend fun advertisedActivityError(
        compactMark: String,
        type: String,
        issuer: String,
        configuration: EntityConfigurationStatement,
        now: Long,
    ): FederationError? {
        val metadata = configuration.metadata?.get("federation_entity") as? JsonObject ?: return null
        val advertised = metadata["federation_trust_mark_status_endpoint"] ?: return null
        fun invalid(reason: String) = TrustMarkInvalidError(trustMarkId = type, reason = reason)
        val primitive = advertised as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isBlank()) return invalid("Advertised Trust Mark status endpoint is invalid")
        val endpoint = primitive.content
        val url = Url(endpoint)
        if (url.protocol != URLProtocol.HTTPS || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || '#' in endpoint) {
            return invalid("Advertised Trust Mark status endpoint must be credential-free HTTPS without a fragment")
        }
        val response = context.httpResolver.postJwt(endpoint, Parameters.build { append("trust_mark", compactMark) })
        val decoded = decodeJWTComponents(response)
        if (decoded.header.typ != "trust-mark-status-response+jwt" || decoded.header.kid.isNullOrBlank()) return invalid("Trust Mark status response has invalid type or key identifier")
        val key = configuration.jwks.propertyKeys.orEmpty().singleOrNull { it.kid == decoded.header.kid }
            ?: return invalid("Trust Mark status response signing key is not an issuer Federation Entity Key")
        if (!context.jwtService.verifyJwtSignature(response, key)) return invalid("Trust Mark status response signature is invalid")
        val claims = decoded.payload
        fun text(name: String): String? = (claims[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text("iss") != issuer || text("trust_mark") != compactMark) return invalid("Trust Mark status response does not match issuer and queried mark")
        val issuedAt = (claims["iat"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        if (issuedAt == null || !issuedAt.isFinite() || issuedAt > now.toDouble() + 5) return invalid("Trust Mark status response has invalid issued-at time")
        if (claims.containsKey("exp")) {
            val expiry = (claims["exp"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (expiry == null || !expiry.isFinite() || expiry <= now.toDouble()) return invalid("Trust Mark status response is expired or has invalid expiry")
        }
        return if (text("status") == "active") null else invalid("Advertised issuer status does not establish an active Trust Mark")
    }

    /**
     * Trust in the Trust Mark Issuer comes before trust in the Trust Mark (OpenID Federation 1.1 §7.3), and the
     * issuer's keys used for its signature (step 7) and its status responses come from that trust. For the Trust
     * Anchor itself that is the Trust Anchor Entity Configuration the caller trusts. Any other issuer needs a valid
     * Trust Chain to the Trust Anchor (§10), verified with [trustAnchorKeys]; its keys are those of the issuer's Entity
     * Configuration inside that verified chain, never of a separately fetched copy.
     */
    private suspend fun establishIssuerTrust(
        trustMarkIssuer: String,
        trustAnchorId: String,
        trustAnchorConfig: EntityConfigurationStatement,
        recognition: TrustMarkRecognition,
        timeToUse: Long
    ): IdkResult<EntityConfigurationStatement, FederationError> {
        if (trustMarkIssuer == trustAnchorId) {
            return IdkResult.ok(trustAnchorConfig)
        }
        val trustAnchorKeys = trustAnchorConfig.jwks.propertyKeys.orEmpty()

        when (recognition) {
            TrustMarkRecognition.ANYONE_MAY_ISSUE,
            TrustMarkRecognition.AUTHORIZED_ISSUERS,
            TrustMarkRecognition.OWNER_DELEGATION -> {
                if (trustAnchorKeys.isEmpty()) {
                    return IdkResult.err(TrustMarkInvalidError(trustMarkId = "unknown", reason = "The Trust Anchor Entity Configuration has no keys"))
                }
                val chainResult = resolveTrustChainCommand.execute(
                    ResolveTrustChainArgs(
                        entityIdentifier = trustMarkIssuer,
                        trustAnchors = arrayOf(trustAnchorId),
                        maxDepth = 5,
                        trustAnchorKeys = mapOf(trustAnchorId to trustAnchorKeys),
                    )
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
                    currentTime = timeToUse,
                    trustAnchorPublicKeys = trustAnchorKeys,
                )
                if (verifyResult.isErr || !verifyResult.value.isValid) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = "unknown",
                        reason = "Trust Chain verification failed for Trust Mark issuer '$trustMarkIssuer'"
                    ))
                }
                val issuerConfig = runCatching {
                    context.json.decodeFromJsonElement(
                        EntityConfigurationStatement.serializer(), decodeJWTComponents(chain.first()).payload
                    )
                }.getOrNull()
                if (issuerConfig == null || issuerConfig.iss != trustMarkIssuer || issuerConfig.sub != trustMarkIssuer) {
                    return IdkResult.err(TrustMarkInvalidError(
                        trustMarkId = "unknown",
                        reason = "The verified Trust Chain does not start with the Entity Configuration of '$trustMarkIssuer'"
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
        return validateTrustMarkDelegation(context.jwtService, delegationJwt, trustMarkId, trustMarkIssuer, owner, timeToUse)
    }
}

private const val DELEGATION_TYP = "trust-mark-delegation+jwt"
private const val DELEGATION_CLOCK_SKEW_SECONDS = 5L

/**
 * Validates a Trust Mark delegation (OpenID Federation 1.1 section 7.2.2): a signed `trust-mark-delegation+jwt` from
 * [owner] to [trustMarkIssuer] for [trustMarkType], current at [timeToUse] and signed with one of the owner's keys.
 */
internal suspend fun validateTrustMarkDelegation(
    jwtService: JwtService,
    delegationJwt: String,
    trustMarkType: String,
    trustMarkIssuer: String,
    owner: TrustMarkOwner,
    timeToUse: Long,
): IdkResult<TrustMarkValidationResponse, FederationError> {
    val trustMarkId = trustMarkType
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
    val delIss = decodedDelegation.payload["iss"]?.jsonPrimitive?.contentOrNull
    if (delIss != ownerSub) {
        return IdkResult.err(TrustMarkInvalidError(
            trustMarkId = trustMarkId,
            reason = "Delegation issuer does not match Trust Mark owner"
        ))
    }

    // §7.2.2 steps 6–7: iat / optional exp
    val delIat = TrustMarkValidation.numericDate(decodedDelegation.payload["iat"])
    if (delIat == null || delIat > timeToUse + DELEGATION_CLOCK_SKEW_SECONDS) {
        return IdkResult.err(TrustMarkInvalidError(
            trustMarkId = trustMarkId,
            reason = "Delegation has invalid or future iat"
        ))
    }
    val delExpEl = decodedDelegation.payload["exp"]
    if (delExpEl != null) {
        val delExp = TrustMarkValidation.numericDate(delExpEl)
        if (delExp == null || delExp <= timeToUse - DELEGATION_CLOCK_SKEW_SECONDS) {
            return IdkResult.err(TrustMarkInvalidError(
                trustMarkId = trustMarkId,
                reason = "Delegation has expired"
            ))
        }
    }

    // §7.2.1: trust_mark_type is REQUIRED in a delegation; §7.2.2 step 8: it matches the Trust Mark's type.
    val delType = decodedDelegation.payload["trust_mark_type"]?.jsonPrimitive?.contentOrNull
    if (delType != trustMarkId) {
        return IdkResult.err(TrustMarkInvalidError(
            trustMarkId = trustMarkId,
            reason = if (delType == null) "Delegation has no trust_mark_type" else "Delegation trust_mark_type does not match Trust Mark"
        ))
    }

    // §7.2.2 step 9: signature with owner keys
    val ownerKeys: Array<Jwk> = owner.jwks.propertyKeys.toTypedArray()
    if (ownerKeys.isEmpty()) return IdkResult.err(TrustMarkInvalidError(trustMarkId, "No JWKS found for Trust Mark owner"))

    val delegationKey = findKeyInJwks(ownerKeys, decodedDelegation.header.kid)
        ?: return IdkResult.err(TrustMarkInvalidError(
            trustMarkId = trustMarkId,
            reason = "Delegation signing key not found in owner's JWKS"
        ))

    if (!jwtService.verifyJwtSignature(delegationJwt, delegationKey)) {
        return IdkResult.err(SignatureVerificationFailedError(
            reason = "Delegation signature verification failed",
            keyId = decodedDelegation.header.kid
        ))
    }

    return IdkResult.ok(TrustMarkValidationResponse(true))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyTrustMarkDelegationCommand>())
class VerifyTrustMarkDelegationCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
) : ExecutionScopedCommandAdapter<VerifyTrustMarkDelegationArgs, TrustMarkValidationResponse, FederationError>(
    id = VerifyTrustMarkDelegationCommand.COMMAND_ID,
    execution = execution
), VerifyTrustMarkDelegationCommand {
    override suspend fun doExecute(
        args: VerifyTrustMarkDelegationArgs,
        applyDuring: (VerifyTrustMarkDelegationArgs) -> VerifyTrustMarkDelegationArgs
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val input = applyDuring(args)
        val owner = input.trustAnchorConfig.trustMarkOwners?.get(input.trustMarkType)
            ?: return IdkResult.err(TrustMarkNotRecognizedError(input.trustMarkType, input.trustAnchorConfig.iss))
        return validateTrustMarkDelegation(context.jwtService, input.delegation, input.trustMarkType, input.issuer, owner,
            input.currentTime ?: getCurrentEpochTimeSeconds())
    }
}

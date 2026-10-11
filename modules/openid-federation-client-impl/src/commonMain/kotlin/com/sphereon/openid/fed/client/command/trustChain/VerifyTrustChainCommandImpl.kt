package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Verifies Trust Chains per OpenID Federation 1.1 §3.2, §4, §6.2, §10.2.
 *
 * Includes RFC 5280-style naming constraints, structural Entity Statement checks,
 * and requires out-of-band Trust Anchor public keys as the cryptographic root of trust.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyTrustChainCommand>())
class VerifyTrustChainCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext
) : ExecutionScopedCommandAdapter<VerifyTrustChainArgs, VerifyTrustChainResponse, FederationError>(
    id = VerifyTrustChainCommand.COMMAND_ID,
    execution = execution
), VerifyTrustChainCommand {

    private val logger = execution.federationLogger("VerifyTrustChainCommand")

    override suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?,
        trustAnchorPublicKeys: List<Jwk>?
    ): IdkResult<VerifyTrustChainResponse, FederationError> {
        return execute(
            VerifyTrustChainArgs(trustChain, trustAnchor, currentTime, trustAnchorPublicKeys)
        )
    }

    override suspend fun doExecute(
        args: VerifyTrustChainArgs,
        applyDuring: (VerifyTrustChainArgs) -> VerifyTrustChainArgs
    ): IdkResult<VerifyTrustChainResponse, FederationError> {
        val (chain, trustAnchor, currentTime, trustAnchorPublicKeysArg) = applyDuring(args)

        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()
        TrustChainTopology.validateMinimumLength(chain.size)?.let { reason ->
            return IdkResult.err(TrustChainValidationFailedError(entityId = "unknown", reason = reason))
        }

        try {
            val statements = chain.map { decodeJWTComponents(it) }

            // --- Topology: length 1 = Trust Anchor as subject (self-signed EC only) ---
            if (chain.size == 1) {
                return verifyTrustAnchorOnlyChain(
                    jwt = chain[0],
                    statement = statements[0],
                    trustAnchor = trustAnchor,
                    timeToUse = timeToUse,
                    trustAnchorPublicKeysArg = trustAnchorPublicKeysArg,
                )
            }

            // --- Structural + crypto checks for each statement ---
            for (j in statements.indices) {
                val statement = statements[j]
                val entityId = statement.payload["sub"]?.jsonPrimitive?.contentOrNull ?: "unknown"

                val structural = EntityStatementValidation.validateStructure(
                    statement = statement,
                    currentTimeSeconds = timeToUse,
                    position = j,
                    understoodCriticalClaims = context.understoodCriticalClaims,
                )
                if (!structural.ok) {
                    return IdkResult.err(TrustChainValidationFailedError(
                        entityId = entityId,
                        reason = structural.reason ?: "Structural validation failed at $j"
                    ))
                }

                if (j == 0) {
                    val iss = statement.payload["iss"]?.jsonPrimitive?.content
                    val sub = statement.payload["sub"]?.jsonPrimitive?.content
                    if (iss != sub) {
                        return IdkResult.err(TrustChainValidationFailedError(
                            entityId = sub ?: "unknown",
                            reason = "First statement must have iss == sub (Entity Configuration)"
                        ))
                    }
                    if (!verifySignatureWithOwnJwks(chain[j])) {
                        return IdkResult.err(TrustChainValidationFailedError(
                            entityId = sub ?: "unknown",
                            reason = "First statement signature verification failed"
                        ))
                    }
                }

                if (j < statements.size - 1) {
                    val currentIss = statement.payload["iss"]?.jsonPrimitive?.content
                    val nextSub = statements[j + 1].payload["sub"]?.jsonPrimitive?.content
                    if (currentIss != nextSub) {
                        return IdkResult.err(TrustChainValidationFailedError(
                            entityId = entityId,
                            reason = "Statement chain broken between positions $j and ${j + 1}"
                        ))
                    }
                    // ES[j] is signed with a key from ES[j+1].jwks (§4)
                    if (!verifySignatureWithNextJwks(chain[j], chain[j + 1])) {
                        return IdkResult.err(TrustChainValidationFailedError(
                            entityId = entityId,
                            reason = "Signature verification failed for statement $j with next statement's keys"
                        ))
                    }
                }

                if (j == statements.size - 1) {
                    val lastIss = statement.payload["iss"]?.jsonPrimitive?.content
                    val lastSub = statement.payload["sub"]?.jsonPrimitive?.content
                    val lastIsTaEc = lastIss != null && lastIss == lastSub

                    if (lastIsTaEc) {
                        // Full chain ending with Trust Anchor Entity Configuration
                        if (trustAnchor != null && lastIss != trustAnchor) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = lastIss ?: "unknown",
                                reason = "Last statement issuer does not match trust anchor"
                            ))
                        }
                        val taId = lastIss ?: "unknown"
                        val oobKeys = resolveOobTrustAnchorKeys(taId, trustAnchorPublicKeysArg)
                        val taSigOk = verifyTrustAnchorSignature(chain[j], taId, oobKeys)
                        if (taSigOk.isErr) {
                            return taSigOk.map { VerifyTrustChainResponse(false) }
                        }
                    } else {
                        // TA EC omitted: last entry is TA Subordinate Statement; verify with OOB TA keys (§4)
                        if (lastIss == null) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = "unknown",
                                reason = "Last Subordinate Statement missing iss (Trust Anchor)"
                            ))
                        }
                        if (trustAnchor != null && lastIss != trustAnchor) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = lastIss,
                                reason = "Last Subordinate Statement issuer does not match trust anchor"
                            ))
                        }
                        val oobKeys = resolveOobTrustAnchorKeys(lastIss, trustAnchorPublicKeysArg)
                        if (oobKeys.isEmpty()) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = lastIss,
                                reason = "Trust chain ends with Subordinate Statement but no out-of-band " +
                                    "Trust Anchor keys were provided for '$lastIss' (required when TA EC is omitted)"
                            ))
                        }
                        val signature = TrustChainValidation.verifyStatementSignature(context.jwtService, chain[j], oobKeys)
                        if (!signature.kidFound) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = lastIss,
                                reason = "Trust Anchor signing kid '${statement.header.kid}' not in out-of-band keys"
                            ))
                        }
                        if (!signature.signatureValid) {
                            return IdkResult.err(TrustChainValidationFailedError(
                                entityId = lastIss,
                                reason = "Last Subordinate Statement signature failed against out-of-band TA keys"
                            ))
                        }
                    }
                }
            }

            // Subject EC authority_hints must include immediate superior (ES[1].iss) when present
            if (statements.size >= 2 &&
                EntityStatementValidation.isSubordinateStatement(statements[1].payload)
            ) {
                val authorityLink = EntityStatementValidation.validateAuthorityHintsLink(
                    subjectEntityConfiguration = statements[0],
                    superiorSubordinateStatement = statements[1]
                )
                if (!authorityLink.ok) {
                    return IdkResult.err(TrustChainValidationFailedError(
                        entityId = statements[0].payload["sub"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                        reason = authorityLink.reason ?: "authority_hints validation failed"
                    ))
                }
            }

            val constraintFailure = TrustChainValidation.validateConstraints(statements)
                .firstOrNull { it.outcome == TrustChainConstraintOutcome.FAILED }
            if (constraintFailure != null) {
                return IdkResult.err(TrustChainValidationFailedError(
                    entityId = constraintFailure.failureEntityId ?: "unknown",
                    reason = constraintFailure.reason ?: "Constraint validation failed"
                ))
            }

            logger.debug("Trust chain verification completed successfully (length=${chain.size})")
            return IdkResult.ok(VerifyTrustChainResponse(true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Chain verification failed with exception", e)
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = "unknown",
                reason = "Chain verification failed: ${e.message}",
                exception = e
            ))
        }
    }

    /**
     * OIDFed §4.1: subject of the chain may be a Trust Anchor (self-signed EC only).
     */
    private suspend fun verifyTrustAnchorOnlyChain(
        jwt: String,
        statement: com.sphereon.openid.fed.openapi.models.Jwt,
        trustAnchor: String?,
        timeToUse: Long,
        trustAnchorPublicKeysArg: List<Jwk>?,
    ): IdkResult<VerifyTrustChainResponse, FederationError> {
        val structural = EntityStatementValidation.validateStructure(
            statement = statement,
            currentTimeSeconds = timeToUse,
            position = 0,
            understoodCriticalClaims = context.understoodCriticalClaims,
        )
        if (!structural.ok) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = statement.payload["sub"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                reason = structural.reason ?: "Structural validation failed"
            ))
        }
        val iss = statement.payload["iss"]?.jsonPrimitive?.content
        val sub = statement.payload["sub"]?.jsonPrimitive?.content
        if (iss == null || iss != sub) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = sub ?: "unknown",
                reason = "Single-statement trust chain must be a Trust Anchor Entity Configuration (iss == sub)"
            ))
        }
        if (trustAnchor != null && iss != trustAnchor) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = iss,
                reason = "Single-statement chain issuer does not match trust anchor"
            ))
        }
        val oobKeys = resolveOobTrustAnchorKeys(iss, trustAnchorPublicKeysArg)
        val taSigOk = verifyTrustAnchorSignature(jwt, iss, oobKeys)
        if (taSigOk.isErr) {
            return taSigOk.map { VerifyTrustChainResponse(false) }
        }
        logger.debug("Trust Anchor-only chain verified for $iss")
        return IdkResult.ok(VerifyTrustChainResponse(true))
    }

    private fun resolveOobTrustAnchorKeys(
        trustAnchorId: String,
        argsKeys: List<Jwk>?
    ): List<Jwk> {
        if (!argsKeys.isNullOrEmpty()) return argsKeys
        return context.trustAnchorPublicKeys[trustAnchorId].orEmpty()
    }

    /** Verify the Trust Anchor only against explicitly supplied or exact-anchor configured keys. */
    private suspend fun verifyTrustAnchorSignature(
        taJwt: String,
        trustAnchorId: String,
        oobKeys: List<Jwk>
    ): IdkResult<Unit, FederationError> {
        val decoded = decodeJWTComponents(taJwt)
        if (oobKeys.isEmpty()) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = trustAnchorId,
                reason = "No out-of-band Trust Anchor public keys were provided for '$trustAnchorId'"
            ))
        }
        val signature = TrustChainValidation.verifyStatementSignature(context.jwtService, taJwt, oobKeys)
        if (!signature.kidFound) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = trustAnchorId,
                reason = "Trust Anchor signing kid '${decoded.header.kid}' not found in " +
                    "out-of-band Trust Anchor public keys"
            ))
        }
        if (!signature.signatureValid) {
            return IdkResult.err(TrustChainValidationFailedError(
                entityId = trustAnchorId,
                reason = "Trust Anchor signature verification failed against out-of-band keys"
            ))
        }
        logger.debug("Trust Anchor signature verified with out-of-band keys")
        return IdkResult.ok(Unit)
    }

    private suspend fun verifySignatureWithOwnJwks(jwt: String): Boolean {
        val decoded = decodeJWTComponents(jwt)
        val jwks: Array<Jwk> =
            context.json.decodeFromString(decoded.payload["jwks"]?.jsonObject?.get("keys").toString())
        return TrustChainValidation.verifyStatementSignature(context.jwtService, jwt, jwks.toList()).signatureValid
    }

    private suspend fun verifySignatureWithNextJwks(jwt: String, nextJwt: String): Boolean {
        val decodedNext = decodeJWTComponents(nextJwt)
        val jwks: Array<Jwk> =
            context.json.decodeFromString(decodedNext.payload["jwks"]?.jsonObject?.get("keys").toString())
        return TrustChainValidation.verifyStatementSignature(context.jwtService, jwt, jwks.toList()).signatureValid
    }
}

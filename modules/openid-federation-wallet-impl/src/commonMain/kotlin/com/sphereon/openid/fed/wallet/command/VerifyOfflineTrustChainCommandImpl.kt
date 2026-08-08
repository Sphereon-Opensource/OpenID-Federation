package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.Jwk
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Offline Trust Chain verification using [FederationClient.trustChainVerify] with mandatory
 * out-of-band Trust Anchor keys, plus optional [OfflineTrustChainPolicy] freshness bounds.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyOfflineTrustChainCommand>())
class VerifyOfflineTrustChainCommandImpl(
    execution: SessionExecution,
    private val federationClient: FederationClient,
    private val context: FederationContext,
) : ExecutionScopedCommandAdapter<VerifyOfflineTrustChainArgs, OfflineTrustChainResult, FederationError>(
    id = VerifyOfflineTrustChainCommand.COMMAND_ID,
    execution = execution
), VerifyOfflineTrustChainCommand {

    private val logger = execution.federationLogger("VerifyOfflineTrustChainCommand")

    override suspend fun verifyOfflineTrustChain(
        trustChain: Array<String>,
        trustAnchor: String,
        trustAnchorPublicKeys: List<Jwk>,
        currentTime: Long?,
        policy: OfflineTrustChainPolicy?,
    ): IdkResult<OfflineTrustChainResult, FederationError> {
        return execute(
            VerifyOfflineTrustChainArgs(trustChain, trustAnchor, trustAnchorPublicKeys, currentTime, policy)
        )
    }

    override suspend fun doExecute(
        args: VerifyOfflineTrustChainArgs,
        applyDuring: (VerifyOfflineTrustChainArgs) -> VerifyOfflineTrustChainArgs
    ): IdkResult<OfflineTrustChainResult, FederationError> {
        val (trustChain, trustAnchor, trustAnchorPublicKeys, currentTimeArg, policyArg) = applyDuring(args)
        val currentTime = currentTimeArg ?: getCurrentEpochTimeSeconds()
        val policy = policyArg ?: context.offlineTrustChainPolicy

        if (trustChain.isEmpty()) {
            return IdkResult.err(InvalidRequestError("trust_chain is empty"))
        }
        if (trustAnchorPublicKeys.isEmpty()) {
            return IdkResult.err(
                InvalidRequestError(
                    "trustAnchorPublicKeys is required for offline Trust Chain verification " +
                        "(out-of-band Trust Anchor keys)"
                )
            )
        }

        // Freshness policy before crypto (cheap fail for stale headers)
        val freshness = policy.evaluateChain(trustChain.toList(), currentTime)
        if (!freshness.ok) {
            logger.warn("Offline trust chain rejected by freshness policy: ${freshness.reason}")
            return IdkResult.err(
                TrustChainValidationFailedError(
                    entityId = trustAnchor,
                    reason = freshness.reason ?: "Offline trust chain failed freshness policy",
                )
            )
        }

        logger.debug(
            "Verifying offline trust chain (len=${trustChain.size}) to TA $trustAnchor " +
                "with ${trustAnchorPublicKeys.size} OOB keys; " +
                "policy maxAge=${policy.maxAgeSeconds} minRemaining=${policy.minRemainingSeconds}"
        )

        val verifyResult = federationClient.trustChainVerify(
            trustChain = trustChain,
            trustAnchor = trustAnchor,
            currentTime = currentTime,
            trustAnchorPublicKeys = trustAnchorPublicKeys
        )

        if (verifyResult.isErr) {
            return IdkResult.err(verifyResult.error)
        }
        if (!verifyResult.value.isValid) {
            return IdkResult.err(
                TrustChainValidationFailedError(
                    entityId = trustAnchor,
                    reason = verifyResult.value.errorMessage
                        ?: "Offline trust chain verification returned invalid"
                )
            )
        }

        val detail = buildString {
            append("Trust chain verified offline with out-of-band Trust Anchor keys")
            if (policy.isEnabled) {
                append("; freshness ok")
                freshness.ageSeconds?.let { append(" (age=${it}s") }
                freshness.remainingSeconds?.let { rem ->
                    if (freshness.ageSeconds != null) append(", remaining=${rem}s)")
                    else append(" (remaining=${rem}s)")
                } ?: run {
                    if (freshness.ageSeconds != null) append(")")
                }
            }
        }

        return IdkResult.ok(
            OfflineTrustChainResult(
                valid = true,
                trustAnchor = trustAnchor,
                chainLength = trustChain.size,
                detail = detail,
                ageSeconds = freshness.ageSeconds,
                remainingSeconds = freshness.remainingSeconds,
            )
        )
    }
}

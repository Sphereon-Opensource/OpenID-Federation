package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Wallet profile §8.2 — re-evaluate Wallet Provider Trust Chain for non-revocation.
 *
 * Equivalent to Federation Entity Discovery for the Wallet Provider with entity type
 * `openid_wallet_provider`. Failure to build/verify a Trust Chain means the provider
 * is no longer a trusted participant (revoked / left the federation).
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CheckWalletProviderNonRevocationCommand>())
class CheckWalletProviderNonRevocationCommandImpl(
    execution: SessionExecution,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand,
) : ExecutionScopedCommandAdapter<
    CheckWalletProviderNonRevocationArgs,
    WalletProviderNonRevocationResult,
    FederationError,
    >(
    id = CheckWalletProviderNonRevocationCommand.COMMAND_ID,
    execution = execution,
), CheckWalletProviderNonRevocationCommand {

    override suspend fun checkWalletProviderNonRevocation(
        walletProviderEntityId: String,
        trustAnchors: Array<String>,
        requiredTrustMarks: Array<String>?,
        currentTime: Long?,
    ): IdkResult<WalletProviderNonRevocationResult, FederationError> =
        execute(
            CheckWalletProviderNonRevocationArgs(
                walletProviderEntityId = walletProviderEntityId,
                trustAnchors = trustAnchors,
                requiredTrustMarks = requiredTrustMarks,
                currentTime = currentTime,
            )
        )

    override suspend fun doExecute(
        args: CheckWalletProviderNonRevocationArgs,
        applyDuring: (CheckWalletProviderNonRevocationArgs) -> CheckWalletProviderNonRevocationArgs,
    ): IdkResult<WalletProviderNonRevocationResult, FederationError> {
        val a = applyDuring(args)
        if (a.walletProviderEntityId.isBlank()) {
            return IdkResult.err(InvalidRequestError("walletProviderEntityId is blank"))
        }
        if (a.trustAnchors.isEmpty()) {
            return IdkResult.err(InvalidRequestError("trustAnchors must not be empty"))
        }

        val trust = evaluateEntityTrustCommand.evaluateEntityTrust(
            entityIdentifier = a.walletProviderEntityId,
            trustAnchors = a.trustAnchors,
            entityTypes = arrayOf(DiscoverCredentialIssuersCommand.ENTITY_TYPE_WALLET_PROVIDER),
            requiredTrustMarks = a.requiredTrustMarks,
            currentTime = a.currentTime,
        )

        return if (trust.isOk && trust.value.trusted) {
            IdkResult.ok(
                WalletProviderNonRevocationResult(
                    active = true,
                    walletProviderEntityId = a.walletProviderEntityId,
                    trustAnchor = trust.value.trustAnchor,
                    trustChain = trust.value.trustChain,
                    effectiveMetadata = trust.value.effectiveMetadata,
                    reason = null,
                )
            )
        } else if (trust.isOk) {
            IdkResult.ok(
                WalletProviderNonRevocationResult(
                    active = false,
                    walletProviderEntityId = a.walletProviderEntityId,
                    trustAnchor = trust.value.trustAnchor,
                    trustChain = trust.value.trustChain,
                    effectiveMetadata = trust.value.effectiveMetadata,
                    reason = "Wallet Provider is not trusted under the given Trust Anchors",
                )
            )
        } else {
            // Soft non-revocation failure: return active=false rather than hard Err so wallets
            // can treat periodic checks as a status result (network/config hard errors still Err).
            IdkResult.ok(
                WalletProviderNonRevocationResult(
                    active = false,
                    walletProviderEntityId = a.walletProviderEntityId,
                    trustAnchor = null,
                    trustChain = emptyList(),
                    effectiveMetadata = null,
                    reason = trust.error.message.defaultMessage,
                )
            )
        }
    }
}

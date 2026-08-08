package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.command.discovery.DiscoverEntitiesArgs
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Wallet profile §8.3 — top-down listing of Credential Issuers via federation list endpoints.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DiscoverCredentialIssuersCommand>())
class DiscoverCredentialIssuersCommandImpl(
    execution: SessionExecution,
    private val federationClient: FederationClient,
) : ExecutionScopedCommandAdapter<
    DiscoverCredentialIssuersArgs,
    DiscoverCredentialIssuersResult,
    FederationError,
    >(
    id = DiscoverCredentialIssuersCommand.COMMAND_ID,
    execution = execution,
), DiscoverCredentialIssuersCommand {

    override suspend fun discoverCredentialIssuers(
        trustAnchors: Array<String>,
        startEntityId: String?,
        recursive: Boolean,
        maxDepth: Int,
        verifyTrust: Boolean,
        maxDepthTrustChain: Int,
    ): IdkResult<DiscoverCredentialIssuersResult, FederationError> =
        execute(
            DiscoverCredentialIssuersArgs(
                trustAnchors = trustAnchors,
                startEntityId = startEntityId,
                recursive = recursive,
                maxDepth = maxDepth,
                verifyTrust = verifyTrust,
                maxDepthTrustChain = maxDepthTrustChain,
            )
        )

    override suspend fun doExecute(
        args: DiscoverCredentialIssuersArgs,
        applyDuring: (DiscoverCredentialIssuersArgs) -> DiscoverCredentialIssuersArgs,
    ): IdkResult<DiscoverCredentialIssuersResult, FederationError> {
        val a = applyDuring(args)
        if (a.trustAnchors.isEmpty() && a.startEntityId.isNullOrBlank()) {
            return IdkResult.err(
                InvalidRequestError("Either trustAnchors or startEntityId must be provided")
            )
        }
        val start = a.startEntityId?.takeIf { it.isNotBlank() }
            ?: a.trustAnchors.first()

        val anchors = if (a.trustAnchors.isNotEmpty()) {
            a.trustAnchors
        } else {
            arrayOf(start)
        }

        val result = federationClient.discoverEntities(
            startEntityId = start,
            entityType = DiscoverCredentialIssuersCommand.ENTITY_TYPE_CREDENTIAL_ISSUER,
            recursive = a.recursive,
            maxDepth = a.maxDepth,
            verifyTrust = a.verifyTrust,
            trustAnchors = anchors,
            maxDepthTrustChain = a.maxDepthTrustChain,
        )
        if (result.isErr) return IdkResult.err(result.error)

        val discovered = result.value
        return IdkResult.ok(
            DiscoverCredentialIssuersResult(
                startEntityId = discovered.startEntityId,
                credentialIssuers = discovered.entities,
                listedSuperiors = discovered.listedSuperiors,
                warnings = discovered.warnings,
            )
        )
    }
}

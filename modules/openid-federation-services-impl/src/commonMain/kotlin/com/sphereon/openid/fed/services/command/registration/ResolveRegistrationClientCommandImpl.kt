package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.federationErr
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/** Resolves a federation client from its Trust Chain: Resolved Metadata and published client signing keys. */
internal class RegistrationClientResolver(
    private val chains: RegistrationTrustChains,
    private val keys: EntityTypeKeyResolver,
) {
    suspend fun resolve(
        clientId: String,
        profile: RegistrationProfile,
        trustAnchors: List<RegistrationTrustAnchor>,
        providedChain: List<String>?,
    ): IdkResult<ResolvedRegistrationClient, FederationError> {
        if (!clientId.startsWith("https://")) {
            return federationErr(InvalidRegistrationError(clientId, "client_id must be the client's Entity Identifier"))
        }
        val chainResult = if (providedChain != null) {
            chains.verify(clientId, providedChain, trustAnchors)
        } else {
            chains.resolve(clientId, trustAnchors, startingAuthorityHints = null)
        }
        if (chainResult.isErr) return federationErr(chainResult.error)
        val chain = chainResult.value

        val resolved = resolveRegistrationMetadata(clientId, chain.payloads[0], chain.payloads.drop(1))
        if (resolved.isErr) return federationErr(resolved.error)
        val clientMetadata = resolved.value.entityTypeObject(profile.clientEntityType)
            ?: return federationErr(InvalidMetadataError(clientId, "No ${profile.clientEntityType} metadata in the Resolved Metadata"))
        val signingKeys = keys.signingKeys(clientId, clientMetadata, chain.payloads[0].jwksKeys())
        if (signingKeys.isErr) return federationErr(signingKeys.error)

        return IdkResult.ok(
            ResolvedRegistrationClient(
                clientId = clientId,
                trustAnchor = chain.trustAnchor,
                trustChain = chain.chain,
                resolvedMetadata = resolved.value,
                clientMetadata = clientMetadata,
                signingKeys = signingKeys.value,
                validUntilEpochSeconds = chain.validUntilEpochSeconds,
            )
        )
    }
}

/**
 * Provider-side resolution of a client that uses its Entity Identifier as `client_id` (OpenID Federation for
 * OpenID Connect 1.1 §12.1.1.1.2). The Trust Chain is discovered from the client and verified against the
 * caller's Trust Anchors only.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveRegistrationClientCommand>())
class ResolveRegistrationClientCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    getEntityConfiguration: GetEntityConfigurationCommand,
    context: FederationContext,
    jwtService: JwtService,
) : TypedServiceCommandAdapter<ResolveRegistrationClientArgs, ResolvedRegistrationClient, FederationError>(
    commandId = ResolveRegistrationClientCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<ResolveRegistrationClientArgs>(),
    outputTypeToken = typeToken<ResolvedRegistrationClient>(),
), ResolveRegistrationClientCommand {

    private val clients = RegistrationClientResolver(
        RegistrationTrustChains(resolveTrustChain, verifyTrustChain, getEntityConfiguration),
        EntityTypeKeyResolver(context, jwtService),
    )

    override suspend fun doExecute(
        args: ResolveRegistrationClientArgs,
        applyDuring: (ResolveRegistrationClientArgs) -> ResolveRegistrationClientArgs,
    ): IdkResult<ResolvedRegistrationClient, FederationError> {
        val applied = applyDuring(args)
        return clients.resolve(applied.clientId, applied.profile, applied.trustAnchors, providedChain = null)
    }
}

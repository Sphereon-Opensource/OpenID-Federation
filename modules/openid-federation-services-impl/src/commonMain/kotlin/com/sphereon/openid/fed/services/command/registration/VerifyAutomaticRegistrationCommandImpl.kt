package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwk
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.JsonObject

/**
 * Provider-side Automatic Registration (OpenID Federation for OpenID Connect 1.1 §12.1.1).
 *
 * The client's Trust Chain is verified (the `trust_chain` header when sent, otherwise discovered from the client
 * Entity Identifier), its Resolved Metadata is derived, and the proof is checked with a key the client publishes
 * for the client Entity Type. A proof `jti` is accepted once.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyAutomaticRegistrationCommand>())
class VerifyAutomaticRegistrationCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    context: FederationContext,
    private val jwtService: JwtService,
    private val jtiStore: RegistrationProofJtiStore,
) : TypedServiceCommandAdapter<VerifyAutomaticRegistrationArgs, VerifiedAutomaticRegistration, FederationError>(
    commandId = VerifyAutomaticRegistrationCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<VerifyAutomaticRegistrationArgs>(),
    outputTypeToken = typeToken<VerifiedAutomaticRegistration>(),
), VerifyAutomaticRegistrationCommand {

    private val chains = RegistrationTrustChains(resolveTrustChain, verifyTrustChain)
    private val clients = RegistrationClientResolver(chains, EntityTypeKeyResolver(context, jwtService))

    private class SignedProof(val jws: CompactJws, val kid: String, val jti: String, val exp: Long)

    override suspend fun doExecute(
        args: VerifyAutomaticRegistrationArgs,
        applyDuring: (VerifyAutomaticRegistrationArgs) -> VerifyAutomaticRegistrationArgs,
    ): IdkResult<VerifiedAutomaticRegistration, FederationError> {
        val applied = applyDuring(args)
        val clientId = applied.clientId
        val provider = applied.providerEntityIdentifier
        fun rejected(reason: String) = federationErr<VerifiedAutomaticRegistration>(InvalidRegistrationError(clientId, reason))

        if (provider.isBlank()) return rejected("The provider Entity Identifier is required")

        val now = getCurrentEpochTimeSeconds()
        val signed: SignedProof?
        val providedChain: List<String>?
        val peerChain: List<String>?
        when (val proof = applied.proof) {
            is AutomaticRegistrationProof.RequestObject -> {
                val jws = parseCompactJws(proof.requestObjectJwt) ?: return rejected("Request Object is not a compact JWS")
                signingAlgError(jws.header.text("alg"), applied.allowedSigningAlgs)?.let { return rejected(it) }
                val claims = jws.payload
                if (claims.text("iss") != clientId) return rejected("Request Object iss must be the client Entity Identifier")
                if (claims.text("client_id") != clientId) return rejected("Request Object client_id must be the client Entity Identifier")
                if (!claims.hasSoleAudience(provider)) return rejected("Request Object aud must be exactly the provider Entity Identifier")
                if (claims.containsKey("sub")) return rejected("Request Object must not contain sub")
                signed = signedProof(jws, now) { return rejected("Request Object $it") }
                providedChain = headerChain(jws.header, "trust_chain") { return rejected(it) }
                peerChain = headerChain(jws.header, "peer_trust_chain") { return rejected(it) }
                if (peerChain != null && providedChain == null) {
                    return rejected("peer_trust_chain requires a trust_chain header")
                }
            }
            is AutomaticRegistrationProof.PrivateKeyJwt -> {
                val jws = parseCompactJws(proof.clientAssertionJwt) ?: return rejected("Client assertion is not a compact JWS")
                signingAlgError(jws.header.text("alg"), applied.allowedSigningAlgs)?.let { return rejected(it) }
                val claims = jws.payload
                if (claims.text("iss") != clientId || claims.text("sub") != clientId) {
                    return rejected("Client assertion iss and sub must be the client Entity Identifier")
                }
                if (!claims.hasSoleAudience(provider)) return rejected("Client assertion aud must be exactly the provider Entity Identifier")
                signed = signedProof(jws, now) { return rejected("Client assertion $it") }
                providedChain = null
                peerChain = null
            }
            is AutomaticRegistrationProof.SelfSignedTlsClientCertificate -> {
                if (proof.certificateDerBase64.isBlank()) return rejected("The client certificate is required")
                signed = null
                providedChain = null
                peerChain = null
            }
        }

        val resolved = clients.resolve(clientId, applied.profile, applied.trustAnchors, providedChain)
        if (resolved.isErr) return federationErr(resolved.error)
        val client = resolved.value

        val key: Jwk = when (val proof = applied.proof) {
            is AutomaticRegistrationProof.SelfSignedTlsClientCertificate -> {
                val wanted = proof.certificateDerBase64.filterNot { it.isWhitespace() }
                val matches = client.signingKeys.filter { it.x5c?.firstOrNull()?.filterNot { c -> c.isWhitespace() } == wanted }
                if (matches.size != 1) return rejected("The client certificate is not the x5c of exactly one published signing key")
                matches.single()
            }
            else -> client.signingKeys.firstOrNull { it.kid == signed!!.kid }
                ?: return federationErr(InvalidMetadataError(clientId, "No published signing key with kid '${signed!!.kid}'"))
        }
        if (signed != null && !jwtService.verifiesWith(signed.jws.compact, key)) {
            return rejected("The proof signature does not verify with the client's published key")
        }

        if (peerChain != null) {
            val peer = chains.verify(provider, peerChain, applied.trustAnchors)
            if (peer.isErr) return federationErr(peer.error)
            if (peer.value.trustAnchor != client.trustAnchor) {
                return federationErr(
                    InvalidTrustAnchorError(peer.value.trustAnchor, "The Peer Trust Chain ends at a different Trust Anchor")
                )
            }
        }

        if (signed != null && !jtiStore.recordIfNew(clientId, signed.jti, signed.exp)) {
            return rejected("The proof jti has already been used")
        }

        return IdkResult.ok(
            VerifiedAutomaticRegistration(
                client = client,
                proofKey = key,
                requestClaims = (applied.proof as? AutomaticRegistrationProof.RequestObject)?.let { signed!!.jws.payload },
                peerTrustChain = peerChain,
            )
        )
    }

    private inline fun signedProof(jws: CompactJws, now: Long, rejected: (String) -> Nothing): SignedProof {
        val kid = jws.header.text("kid") ?: rejected("has no kid header")
        val jti = jws.payload.text("jti") ?: rejected("has no jti")
        val exp = jws.payload.epochSeconds("exp") ?: rejected("has no exp")
        if (exp <= now) rejected("has expired")
        jws.payload["iat"]?.let {
            val iat = jws.payload.epochSeconds("iat") ?: rejected("iat is not a number")
            if (iat > now + IAT_SKEW_SECONDS) rejected("iat is in the future")
        }
        return SignedProof(jws, kid, jti, exp)
    }

    private inline fun headerChain(header: JsonObject, name: String, rejected: (String) -> Nothing): List<String>? {
        val element = header[name] ?: return null
        val chain = element.stringList() ?: rejected("$name must be an array of Entity Statements")
        if (chain.isEmpty()) rejected("$name must not be empty")
        return chain
    }

    private companion object {
        const val IAT_SKEW_SECONDS = 300L
    }
}

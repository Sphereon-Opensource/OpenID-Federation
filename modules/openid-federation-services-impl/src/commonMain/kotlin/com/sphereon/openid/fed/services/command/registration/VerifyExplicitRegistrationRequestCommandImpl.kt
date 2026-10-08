package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Provider-side processing of an Explicit Registration request (OpenID Federation for OpenID Connect 1.1 §12.2.2).
 *
 * The request Entity Configuration is validated as an Entity Statement addressed to this provider. Its Trust Chain
 * is the request body chain, the `trust_chain` header, or one discovered from the request `authority_hints`; in every
 * case the Immediate Superior must list the key that signed the request. The request metadata, under the policies
 * of that chain, is what the provider registers from.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyExplicitRegistrationRequestCommand>())
class VerifyExplicitRegistrationRequestCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    private val context: FederationContext,
    private val jwtService: JwtService,
) : TypedServiceCommandAdapter<VerifyExplicitRegistrationRequestArgs, VerifiedExplicitRegistrationRequest, FederationError>(
    commandId = VerifyExplicitRegistrationRequestCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<VerifyExplicitRegistrationRequestArgs>(),
    outputTypeToken = typeToken<VerifiedExplicitRegistrationRequest>(),
), VerifyExplicitRegistrationRequestCommand {

    private val chains = RegistrationTrustChains(resolveTrustChain, verifyTrustChain)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doExecute(
        args: VerifyExplicitRegistrationRequestArgs,
        applyDuring: (VerifyExplicitRegistrationRequestArgs) -> VerifyExplicitRegistrationRequestArgs,
    ): IdkResult<VerifiedExplicitRegistrationRequest, FederationError> {
        val applied = applyDuring(args)
        val provider = applied.providerEntityIdentifier
        var subject = "unknown"
        fun rejected(reason: String) =
            federationErr<VerifiedExplicitRegistrationRequest>(InvalidRegistrationError(subject, reason))

        if (provider.isBlank()) return rejected("The provider Entity Identifier is required")

        val bodyChain: List<String>?
        val requestCompact: String
        when (applied.contentType.substringBefore(';').trim().lowercase()) {
            FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE -> {
                bodyChain = null
                requestCompact = applied.body.trim()
            }
            FederationRegistration.TRUST_CHAIN_CONTENT_TYPE -> {
                bodyChain = try {
                    json.parseToJsonElement(applied.body).stringList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (bodyChain.isNullOrEmpty()) return rejected("A Trust Chain body must be a non-empty array of Entity Statements")
                requestCompact = bodyChain[0]
            }
            else -> return rejected("Unsupported registration request content type '${applied.contentType}'")
        }

        val request = parseCompactJws(requestCompact) ?: return rejected("The request is not a compact JWS")
        val claims = request.payload
        val clientId = claims.text("iss") ?: return rejected("The request has no iss")
        subject = clientId
        if (claims.text("sub") != clientId) return rejected("The request iss and sub must both be the client Entity Identifier")
        if (!clientId.startsWith("https://")) return rejected("The client Entity Identifier must be an https URL")

        val headerChain = request.header["trust_chain"]?.let { it.stringList() ?: return rejected("trust_chain must be an array of Entity Statements") }
        val peerChain = request.header["peer_trust_chain"]?.let { it.stringList() ?: return rejected("peer_trust_chain must be an array of Entity Statements") }
        if (bodyChain != null && (headerChain != null || peerChain != null)) {
            return rejected("A Trust Chain request body must not carry trust_chain or peer_trust_chain headers")
        }
        if (peerChain != null && headerChain == null) return rejected("peer_trust_chain requires a trust_chain header")

        val now = getCurrentEpochTimeSeconds()
        val structure = try {
            val header = json.decodeFromJsonElement<JwtHeader>(
                JsonObject(request.header.filterKeys { it != "trust_chain" && it != "peer_trust_chain" })
            )
            EntityStatementValidation.validateStructure(
                statement = Jwt(header, claims, requestCompact.substringAfterLast('.')),
                currentTimeSeconds = now,
                position = 0,
                understoodCriticalClaims = context.understoodCriticalClaims,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return rejected("The request header is not a valid Entity Statement header: ${e.message}")
        }
        if (!structure.ok) return rejected(structure.reason ?: "The request is not a valid Entity Configuration")
        if (!claims.hasSoleAudience(provider)) return rejected("The request aud must be exactly the provider Entity Identifier")
        val authorityHints = claims["authority_hints"].stringList()
        if (authorityHints.isNullOrEmpty()) return rejected("The request must contain authority_hints")
        val requestMetadata = claims["metadata"] as? JsonObject ?: return rejected("The request must contain metadata")
        if (requestMetadata.entityTypeObject(applied.profile.clientEntityType) == null) {
            return federationErr(InvalidMetadataError(clientId, "The request metadata has no ${applied.profile.clientEntityType}"))
        }
        val clientJwks = claims["jwks"] as? JsonObject ?: return rejected("The request jwks must be a JWK Set")
        val requestKid = request.header.text("kid") ?: return rejected("The request has no kid header")
        val ownKey = claims.jwksKeys().firstOrNull { it.kid == requestKid }
            ?: return rejected("The request is not signed with a key from its own jwks")
        if (!jwtService.verifiesWith(requestCompact, ownKey)) return rejected("The request signature does not verify")

        val chainResult = when {
            bodyChain != null -> chains.verify(clientId, bodyChain, applied.trustAnchors)
            headerChain != null -> chains.verify(clientId, headerChain, applied.trustAnchors)
            else -> chains.resolve(clientId, applied.trustAnchors, startingAuthorityHints = authorityHints)
        }
        if (chainResult.isErr) return federationErr(chainResult.error)
        val chain = chainResult.value

        if (chain.immediateSuperior !in authorityHints) {
            return rejected("The Trust Chain does not continue at one of the request authority_hints")
        }
        val vouchedKey = chain.immediateSuperiorKeys.firstOrNull { it.kid == requestKid }
            ?: return rejected("The Immediate Superior does not list the key that signed the request")
        if (!jwtService.verifiesWith(requestCompact, vouchedKey)) {
            return rejected("The request does not verify with the key its Immediate Superior lists")
        }

        val resolved = resolveRegistrationMetadata(clientId, claims, chain.payloads.drop(1))
        if (resolved.isErr) return federationErr(resolved.error)
        val clientMetadata = resolved.value.entityTypeObject(applied.profile.clientEntityType)
            ?: return federationErr(
                InvalidMetadataError(clientId, "No ${applied.profile.clientEntityType} metadata after the Trust Chain policies")
            )

        if (peerChain != null) {
            val peer = chains.verify(provider, peerChain, applied.trustAnchors)
            if (peer.isErr) return federationErr(peer.error)
            if (peer.value.trustAnchor != chain.trustAnchor) {
                return federationErr(
                    InvalidTrustAnchorError(peer.value.trustAnchor, "The Peer Trust Chain ends at a different Trust Anchor")
                )
            }
        }

        val requestExp = EntityStatementValidation.conservativeExpiryEpochSeconds(claims["exp"])
            ?: return rejected("The request has an invalid exp")

        return IdkResult.ok(
            VerifiedExplicitRegistrationRequest(
                clientEntityIdentifier = clientId,
                providerEntityIdentifier = provider,
                profile = applied.profile,
                trustAnchor = chain.trustAnchor,
                trustChain = chain.chain,
                immediateSuperior = chain.immediateSuperior,
                clientJwks = clientJwks,
                entityTypes = requestMetadata.keys.toList(),
                resolvedMetadata = resolved.value,
                clientMetadata = clientMetadata,
                validUntilEpochSeconds = minOf(chain.validUntilEpochSeconds, requestExp),
                peerTrustChain = peerChain,
            )
        )
    }
}

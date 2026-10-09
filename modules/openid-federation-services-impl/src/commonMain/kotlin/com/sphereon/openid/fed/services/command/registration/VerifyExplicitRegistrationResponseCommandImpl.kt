package com.sphereon.openid.fed.services.command.registration

import com.sphereon.openid.fed.client.command.trustChain.TrustAnchorKeyResolver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.federationErr
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client-side processing of an Explicit Registration response (OpenID Federation for OpenID Connect 1.1 §12.2.5).
 *
 * The response must be signed with a key that the provider's Immediate Superior lists in the provider Trust Chain
 * resolved for the request, be addressed to this client, name one of the client's Trust Anchors, and continue at one
 * of the requested `authority_hints`. Its metadata must cover the same Entity Types as the request and satisfy the
 * policies of the client's Trust Chain to that Trust Anchor.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyExplicitRegistrationResponseCommand>())
class VerifyExplicitRegistrationResponseCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    trustAnchorKeys: TrustAnchorKeyResolver,
    private val jwtService: JwtService,
) : TypedServiceCommandAdapter<VerifyExplicitRegistrationResponseArgs, VerifiedExplicitRegistration, FederationError>(
    commandId = VerifyExplicitRegistrationResponseCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<VerifyExplicitRegistrationResponseArgs>(),
    outputTypeToken = typeToken<VerifiedExplicitRegistration>(),
), VerifyExplicitRegistrationResponseCommand {

    private val chains = RegistrationTrustChains(resolveTrustChain, verifyTrustChain, trustAnchorKeys)

    override suspend fun doExecute(
        args: VerifyExplicitRegistrationResponseArgs,
        applyDuring: (VerifyExplicitRegistrationResponseArgs) -> VerifyExplicitRegistrationResponseArgs,
    ): IdkResult<VerifiedExplicitRegistration, FederationError> {
        val applied = applyDuring(args)
        val request = applied.request
        val client = request.clientEntityIdentifier
        val provider = request.providerEntityIdentifier
        fun rejected(reason: String) = federationErr<VerifiedExplicitRegistration>(InvalidRegistrationError(client, reason))

        val response = parseCompactJws(applied.responseJwt) ?: return rejected("The response is not a compact JWS")
        if (response.header.text("typ") != FederationRegistration.EXPLICIT_RESPONSE_TYP) {
            return rejected("The response typ must be ${FederationRegistration.EXPLICIT_RESPONSE_TYP}")
        }
        asymmetricAlgError(response.header.text("alg"))?.let { return rejected(it) }
        val kid = response.header.text("kid") ?: return rejected("The response has no kid header")
        val claims = response.payload
        val now = getCurrentEpochTimeSeconds()
        if (claims.text("iss") != provider) return rejected("The response is not issued by the provider")
        if (claims.text("sub") != client) return rejected("The response subject is not this client")
        if (!claims.hasSoleAudience(client)) return rejected("The response aud must be exactly this client")
        if (claims.epochSeconds("iat") == null) return rejected("The response has no iat")
        val exp = EntityStatementValidation.conservativeExpiryEpochSeconds(claims["exp"])
            ?: return rejected("The response has an invalid exp")
        if (exp <= now) return rejected("The response has expired")

        val providerPayloads = try {
            request.providerTrustChain.map { decodeJWTComponents(it).payload }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return rejected("The provider Trust Chain of the request cannot be decoded: ${e.message}")
        }
        if (providerPayloads.size < 2) return rejected("The provider Trust Chain of the request has no Immediate Superior")
        val providerValidUntil = providerPayloads.minOf {
            EntityStatementValidation.conservativeExpiryEpochSeconds(it["exp"]) ?: return rejected("The provider Trust Chain has an invalid exp")
        }
        if (providerValidUntil <= now) return rejected("The provider Trust Chain of the request has expired")
        val vouchedKey = providerPayloads[1].jwksKeys().firstOrNull { it.kid == kid }
            ?: return rejected("The provider's Immediate Superior does not list the key that signed the response")
        if (!jwtService.verifiesWith(applied.responseJwt, vouchedKey)) return rejected("The response signature does not verify")

        val trustAnchor = claims.text("trust_anchor") ?: return rejected("The response has no trust_anchor")
        val anchors = applied.trustAnchors.filter { it.entityIdentifier == trustAnchor }
        if (anchors.isEmpty()) return federationErr(InvalidTrustAnchorError(trustAnchor, "Not a Trust Anchor of this client"))
        if (request.clientTrustChain != null && trustAnchor != request.providerTrustAnchor) {
            return federationErr(InvalidTrustAnchorError(trustAnchor, "Not the Trust Anchor of the Trust Chains in the request"))
        }
        val hints = claims["authority_hints"].stringList()
        if (hints == null || hints.size != 1) return rejected("The response authority_hints must hold exactly one Immediate Superior")
        val hint = hints.single()
        if (hint !in request.authorityHints) return rejected("The response authority_hints entry was not requested")
        val clientChain = chains.resolve(client, anchors, startingAuthorityHints = listOf(hint))
        if (clientChain.isErr) return federationErr(clientChain.error)

        val metadata = claims["metadata"] as? JsonObject ?: return rejected("The response has no metadata")
        if (metadata.keys != request.entityTypes.toSet()) {
            return federationErr(InvalidMetadataError(client, "The registered Entity Types differ from the request"))
        }
        val clientMetadata = metadata.entityTypeObject(request.profile.clientEntityType)
            ?: return federationErr(InvalidMetadataError(client, "The response has no ${request.profile.clientEntityType} metadata"))
        val clientId = clientMetadata.text("client_id")
            ?: return federationErr(InvalidMetadataError(client, "The registered metadata has no client_id"))
        val registeredLeaf = buildJsonObject {
            put("iss", client)
            put("sub", client)
            put("metadata", metadata)
        }
        val policyCheck = resolveRegistrationMetadata(client, registeredLeaf, clientChain.value.payloads.drop(1))
        if (policyCheck.isErr) return federationErr(policyCheck.error)

        return IdkResult.ok(
            VerifiedExplicitRegistration(
                clientEntityIdentifier = client,
                providerEntityIdentifier = provider,
                clientId = clientId,
                trustAnchor = trustAnchor,
                registeredMetadata = metadata,
                clientMetadata = clientMetadata,
                clientTrustChain = clientChain.value.chain,
                validUntilEpochSeconds = minOf(exp, clientChain.value.validUntilEpochSeconds, providerValidUntil),
            )
        )
    }
}

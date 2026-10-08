package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject

/**
 * Client registration for OpenID Federation for OpenID Connect 1.1, Section 12.
 *
 * Automatic Registration (§12.1) and Explicit Registration (§12.2) establish trust between a client
 * (`openid_relying_party` or `oauth_client`) and a provider (`openid_provider` or `oauth_authorization_server`)
 * through Trust Chains. These commands carry the protocol: request and response construction, Trust Chain
 * resolution and verification, metadata policy, signatures, audiences and lifetimes. The host decides which
 * Trust Anchors it accepts, assigns client identifiers and credentials, and stores registrations.
 */
object FederationRegistration {
    const val ENTITY_STATEMENT_CONTENT_TYPE = "application/entity-statement+jwt"
    const val TRUST_CHAIN_CONTENT_TYPE = "application/trust-chain+json"
    const val EXPLICIT_RESPONSE_CONTENT_TYPE = "application/explicit-registration-response+jwt"
    const val EXPLICIT_RESPONSE_TYP = "explicit-registration-response+jwt"
    const val AUTOMATIC = "automatic"
    const val EXPLICIT = "explicit"
}

/** The client and provider Entity Types a registration is made for (§12: OpenID Connect or another OAuth 2.0 profile). */
enum class RegistrationProfile(val clientEntityType: String, val providerEntityType: String) {
    OPENID_CONNECT("openid_relying_party", "openid_provider"),
    OAUTH2("oauth_client", "oauth_authorization_server"),
}

/** A Trust Anchor the caller accepts, with its out-of-band public keys as the root of trust. */
data class RegistrationTrustAnchor(
    val entityIdentifier: String,
    val publicKeys: List<Jwk>,
)

// -----------------------------------------------------------------------------
// Client resolution (provider side, §12.1.1.1.2)
// -----------------------------------------------------------------------------

data class ResolveRegistrationClientArgs(
    /** A `client_id` that is the client's Entity Identifier. */
    val clientId: String,
    val profile: RegistrationProfile,
    val trustAnchors: List<RegistrationTrustAnchor>,
)

/**
 * A federation client the provider can register automatically: a verified Trust Chain, the client's Resolved
 * Metadata and the signing keys it publishes for the client Entity Type. This is not proof that a request came from
 * the client; a Request Object or client authentication must still verify with one of [signingKeys].
 */
data class ResolvedRegistrationClient(
    val clientId: String,
    val trustAnchor: String,
    val trustChain: List<String>,
    val resolvedMetadata: JsonObject,
    val clientMetadata: JsonObject,
    val signingKeys: List<Jwk>,
    val validUntilEpochSeconds: Long,
)

interface ResolveRegistrationClientCommand :
    ServiceCommand<ResolveRegistrationClientArgs, ResolvedRegistrationClient, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.resolve-client"
    }
}

// -----------------------------------------------------------------------------
// Automatic Registration (provider side, §12.1.1)
// -----------------------------------------------------------------------------

/** How the client proves control of a key in its client-type JWK Set (§12.1.1.1, §12.1.1.2). */
sealed interface AutomaticRegistrationProof {
    /** A signed Request Object at the Authorization Endpoint or as a PAR parameter. */
    data class RequestObject(val requestObjectJwt: String) : AutomaticRegistrationProof

    /** `private_key_jwt` client authentication at the PAR endpoint. */
    data class PrivateKeyJwt(val clientAssertionJwt: String) : AutomaticRegistrationProof

    /** `self_signed_tls_client_auth` at the PAR endpoint: the base64 DER client certificate the TLS layer verified. */
    data class SelfSignedTlsClientCertificate(val certificateDerBase64: String) : AutomaticRegistrationProof
}

data class VerifyAutomaticRegistrationArgs(
    /** The provider's Entity Identifier: the only accepted `aud`. */
    val providerEntityIdentifier: String,
    /** The `client_id` of the request: the client's Entity Identifier. */
    val clientId: String,
    val profile: RegistrationProfile,
    val proof: AutomaticRegistrationProof,
    val trustAnchors: List<RegistrationTrustAnchor>,
    /** JWS algorithms the provider accepts for the proof. */
    val allowedSigningAlgs: List<String>,
)

data class VerifiedAutomaticRegistration(
    val client: ResolvedRegistrationClient,
    /** The key that verified the proof. */
    val proofKey: Jwk,
    /** Verified Request Object claims, for [AutomaticRegistrationProof.RequestObject]. */
    val requestClaims: JsonObject?,
    /** The provider-side Peer Trust Chain the client selected, when it sent one (§12.1.1.1.1). */
    val peerTrustChain: List<String>?,
)

interface VerifyAutomaticRegistrationCommand :
    ServiceCommand<VerifyAutomaticRegistrationArgs, VerifiedAutomaticRegistration, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.verify-automatic"
    }
}

// -----------------------------------------------------------------------------
// Explicit Registration request (client side, §12.2.1)
// -----------------------------------------------------------------------------

data class CreateExplicitRegistrationRequestArgs(
    /** The client account that signs the request with its selected Federation Entity Key. */
    val accountId: String,
    val providerEntityIdentifier: String,
    val profile: RegistrationProfile,
    /** The Trust Anchors the client proceeds with; every one must also be a Trust Anchor of the provider. */
    val trustAnchors: List<RegistrationTrustAnchor>,
    /** Immediate Superiors to put in `authority_hints`; each must lead to one of [trustAnchors]. */
    val authorityHints: List<String>,
    /** The metadata to register, keyed by Entity Type; must contain the client Entity Type. */
    val metadata: JsonObject,
    /** Include the client's Trust Chain and the provider's Peer Trust Chain as JWS header parameters. */
    val includeTrustChains: Boolean,
)

data class ExplicitRegistrationRequest(
    val clientEntityIdentifier: String,
    val providerEntityIdentifier: String,
    val profile: RegistrationProfile,
    /** The provider's `federation_registration_endpoint`. */
    val registrationEndpoint: String,
    val contentType: String,
    val body: String,
    val authorityHints: List<String>,
    val entityTypes: List<String>,
    /** The provider Trust Chain the client resolved before sending; it anchors response verification. */
    val providerTrustChain: List<String>,
    val providerTrustAnchor: String,
    /** The client Trust Chain sent in the `trust_chain` header, when included. */
    val clientTrustChain: List<String>?,
)

interface CreateExplicitRegistrationRequestCommand :
    ServiceCommand<CreateExplicitRegistrationRequestArgs, ExplicitRegistrationRequest, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.create-explicit-request"
    }
}

// -----------------------------------------------------------------------------
// Explicit Registration request processing (provider side, §12.2.2)
// -----------------------------------------------------------------------------

data class VerifyExplicitRegistrationRequestArgs(
    val providerEntityIdentifier: String,
    val profile: RegistrationProfile,
    /** The request content type: `application/entity-statement+jwt` or `application/trust-chain+json`. */
    val contentType: String,
    val body: String,
    val trustAnchors: List<RegistrationTrustAnchor>,
)

data class VerifiedExplicitRegistrationRequest(
    val clientEntityIdentifier: String,
    val providerEntityIdentifier: String,
    val profile: RegistrationProfile,
    val trustAnchor: String,
    /** The verified Trust Chain the provider selected; its first element is the client's Entity Configuration. */
    val trustChain: List<String>,
    /** The client's Immediate Superior in [trustChain]. */
    val immediateSuperior: String,
    /** The verbatim `jwks` claim of the request Entity Configuration. */
    val clientJwks: JsonObject,
    /** Entity Types in the request metadata. */
    val entityTypes: List<String>,
    /** Request metadata after the policies of [trustChain], keyed by Entity Type. */
    val resolvedMetadata: JsonObject,
    /** [resolvedMetadata] for the client Entity Type. */
    val clientMetadata: JsonObject,
    /** The registration must not outlive this time (§12.2.2 step 8, §12.3). */
    val validUntilEpochSeconds: Long,
    /** The verified provider-side Peer Trust Chain the client selected, when it sent one. */
    val peerTrustChain: List<String>?,
)

interface VerifyExplicitRegistrationRequestCommand :
    ServiceCommand<VerifyExplicitRegistrationRequestArgs, VerifiedExplicitRegistrationRequest, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.verify-explicit-request"
    }
}

// -----------------------------------------------------------------------------
// Explicit Registration response (provider side, §12.2.3)
// -----------------------------------------------------------------------------

data class SignExplicitRegistrationResponseArgs(
    /** The provider account that signs with its selected Federation Entity Key. */
    val accountId: String,
    val request: VerifiedExplicitRegistrationRequest,
    /** The registered client metadata the provider created; must contain the provisioned `client_id`. */
    val registeredClientMetadata: JsonObject,
    /** Expiration of the registration; not after [VerifiedExplicitRegistrationRequest.validUntilEpochSeconds]. */
    val expiresAtEpochSeconds: Long,
    /** Copy the request `jwks` claim into the response. */
    val includeClientJwks: Boolean,
)

data class ExplicitRegistrationResponse(
    val contentType: String,
    val body: String,
    val clientId: String,
    val expiresAtEpochSeconds: Long,
)

interface SignExplicitRegistrationResponseCommand :
    ServiceCommand<SignExplicitRegistrationResponseArgs, ExplicitRegistrationResponse, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.sign-explicit-response"
    }
}

// -----------------------------------------------------------------------------
// Explicit Registration response processing (client side, §12.2.5)
// -----------------------------------------------------------------------------

data class VerifyExplicitRegistrationResponseArgs(
    val request: ExplicitRegistrationRequest,
    val responseJwt: String,
    /** The client's own Trust Anchors. */
    val trustAnchors: List<RegistrationTrustAnchor>,
)

data class VerifiedExplicitRegistration(
    val clientEntityIdentifier: String,
    val providerEntityIdentifier: String,
    val clientId: String,
    val trustAnchor: String,
    /** Registered metadata keyed by Entity Type, as returned by the provider. */
    val registeredMetadata: JsonObject,
    /** [registeredMetadata] for the client Entity Type, including any provisioned credentials. */
    val clientMetadata: JsonObject,
    /** The client Trust Chain from the response `authority_hints` to `trust_anchor`. */
    val clientTrustChain: List<String>,
    /** Earliest of the response expiration and both Trust Chains (§12.3). */
    val validUntilEpochSeconds: Long,
)

interface VerifyExplicitRegistrationResponseCommand :
    ServiceCommand<VerifyExplicitRegistrationResponseArgs, VerifiedExplicitRegistration, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.registration.verify-explicit-response"
    }
}

/**
 * Replay guard for Automatic Registration proofs: a Request Object or client assertion `jti` is accepted once
 * per client until it expires (§12.1.1.1). Deployments with more than one node bind a shared store.
 */
interface RegistrationProofJtiStore {
    /** @return `true` when newly recorded; `false` for a replay. */
    suspend fun recordIfNew(clientId: String, jti: String, expiresAtEpochSeconds: Long): Boolean
}

package com.sphereon.openid.fed.wallet

import com.sphereon.openid.fed.client.helpers.JwtTrustChainHeader
import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.wallet.command.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Federation Wallet Client facade for evaluating entity trust within
 * OpenID Federation Wallet Architecture.
 *
 * This client provides high-level operations for:
 * - Evaluating whether an entity (credential issuer, verifier, wallet provider) is trusted
 * - Validating endpoint constraints for credential verifiers
 * - Validating CV Authorization Requests (endpoints, federated jwks, dcql_queries)
 * - Verifying wallet attestations through federation trust
 * - Offline Trust Chain verification (JWT trust_chain header + OOB TA keys)
 * - Resolving DCQL trusted authorities via federation
 * - Applying metadata policies from trust chains
 * - Wallet Provider non-revocation checks (wallet profile §8.2)
 * - Credential Issuer discovery via federation list endpoints (wallet profile §8.3)
 */
interface FederationWalletClient {

    /**
     * Evaluate whether an entity is trusted in the federation.
     *
     * Resolves the trust chain, verifies it cryptographically, applies metadata policies,
     * and validates trust marks.
     */
    suspend fun evaluateEntityTrust(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>? = null,
        requiredTrustMarks: Array<String>? = null,
        currentTime: Long? = null
    ): FederationResult<EntityTrustResult>

    /**
     * Validate that a credential verifier's endpoints match its federation metadata.
     *
     * Per the wallet spec Section 7.3, credential verifiers must pre-register
     * their request_uris, response_uris, and redirect_uris.
     */
    suspend fun validateEndpointConstraints(
        entityMetadata: JsonObject,
        requestUri: String? = null,
        responseUri: String? = null,
        redirectUri: String? = null,
        entityType: String = "openid_credential_verifier"
    ): FederationResult<EndpointConstraintsResult>

    /**
     * Validate a Credential Verifier Authorization Request against federation metadata:
     * endpoints, federated jwks precedence over client_metadata, and dcql_queries.
     */
    suspend fun validateCredentialVerifierRequest(
        entityMetadata: JsonObject,
        requestUri: String? = null,
        responseUri: String? = null,
        redirectUri: String? = null,
        clientMetadata: JsonObject? = null,
        requestDcqlQuery: JsonElement? = null
    ): FederationResult<CredentialVerifierRequestValidationResult>

    /**
     * Verify a pre-built Trust Chain offline using out-of-band Trust Anchor keys
     * (e.g. JWT `trust_chain` header without Federation API discovery).
     *
     * @param policy Optional freshness policy override; default from
     *   [com.sphereon.openid.fed.client.context.FederationContext.offlineTrustChainPolicy]
     *   (`oidf.client.offline.trust.chain.*`).
     */
    suspend fun verifyOfflineTrustChain(
        trustChain: Array<String>,
        trustAnchor: String,
        trustAnchorPublicKeys: List<Jwk>,
        currentTime: Long? = null,
        policy: OfflineTrustChainPolicy? = null,
    ): FederationResult<OfflineTrustChainResult>

    /**
     * Extract the OpenID Federation `trust_chain` JOSE header from a compact JWT
     * (wallet attestation, request object, signed metadata, etc.).
     *
     * @return non-empty chain, or null if the header is absent/empty
     * @see JwtTrustChainHeader
     */
    fun extractTrustChainHeader(compactJwt: String): List<String>? =
        JwtTrustChainHeader.extractFromCompactJwt(compactJwt)

    /**
     * Verify a Wallet Attestation JWT: claim/typ profile, optional embedded
     * `trust_chain` header (offline verify with OOB TA keys from context), or
     * network trust evaluation of the Wallet Provider, then signature check.
     */
    suspend fun verifyWalletAttestation(
        walletAttestationJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long? = null
    ): FederationResult<WalletAttestationResult>

    /**
     * Evaluate whether a credential issuer is trusted by any of the
     * DCQL trusted authorities of type "openid_federation".
     */
    suspend fun resolveDcqlTrustedAuthorities(
        credentialIssuerIdentifier: String,
        trustedAuthorities: List<String>,
        currentTime: Long? = null
    ): FederationResult<DcqlTrustResult>

    /**
     * Apply metadata policies from a verified trust chain to derive effective metadata.
     */
    suspend fun applyMetadataPolicy(
        trustChain: Array<String>,
        entityType: String? = null
    ): FederationResult<EffectiveMetadataResult>

    /**
     * Verify a credential issuer through federation trust and validate the credential
     * signature against issuer keys (`openid_credential_issuer.jwks`, with `vc_issuer.jwks`
     * dual-profile fallback).
     *
     * Extracts the issuer identifier from the `fed` claim (or `iss` fallback),
     * evaluates federation trust, and verifies the credential signature.
     */
    suspend fun verifyCredentialIssuer(
        credentialJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long? = null
    ): FederationResult<CredentialIssuerResult>

    /**
     * Validate that a federation entity's metadata conforms to the wallet architecture
     * and/or DIIP profiles after establishing trust.
     *
     * Wallet: organization_name, WP/CI/CV/AS/federation_entity type checks,
     * openid_credential_issuer.jwks. DIIP dual-accepts organization_name and
     * openid_credential_issuer.jwks when strict DIIP fields are absent.
     */
    suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String? = null,
        currentTime: Long? = null,
        profileMode: MetadataProfileMode = MetadataProfileMode.BOTH,
    ): FederationResult<FederationEntityMetadataValidationResult>

    /**
     * Wallet profile §8.2: check that the Wallet Provider is still a trusted (non-revoked)
     * participant by re-resolving and verifying its Trust Chain under [trustAnchors].
     */
    suspend fun checkWalletProviderNonRevocation(
        walletProviderEntityId: String,
        trustAnchors: Array<String>,
        requiredTrustMarks: Array<String>? = null,
        currentTime: Long? = null,
    ): FederationResult<WalletProviderNonRevocationResult>

    /**
     * Wallet profile §8.3: browse the federation for Credential Issuers using list endpoints
     * (top-down from a Trust Anchor / Intermediate), optionally verifying each Trust Chain.
     *
     * @param trustAnchors Trust Anchors (preference order); first is default [startEntityId]
     * @param startEntityId override listing start (defaults to first Trust Anchor)
     * @param recursive walk Intermediate list endpoints
     * @param verifyTrust only include CIs with a valid Trust Chain (default true)
     */
    suspend fun discoverCredentialIssuers(
        trustAnchors: Array<String>,
        startEntityId: String? = null,
        recursive: Boolean = true,
        maxDepth: Int = 5,
        verifyTrust: Boolean = true,
        maxDepthTrustChain: Int = 5,
    ): FederationResult<DiscoverCredentialIssuersResult>
}

package com.sphereon.openid.fed.wallet

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.wallet.command.*
import kotlinx.serialization.json.JsonObject

/**
 * Federation Wallet Client facade for evaluating entity trust within
 * OpenID Federation Wallet Architecture.
 *
 * This client provides high-level operations for:
 * - Evaluating whether an entity (credential issuer, verifier, wallet provider) is trusted
 * - Validating endpoint constraints for credential verifiers
 * - Verifying wallet attestations through federation trust
 * - Resolving DCQL trusted authorities via federation
 * - Applying metadata policies from trust chains
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
     * Verify a Wallet Attestation JWT by resolving the Wallet Provider's
     * federation entity and verifying the attestation signature.
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
     * signature against the issuer's `vc_issuer` keys (DIIP profile).
     *
     * Extracts the issuer identifier from the `fed` claim (or `iss` fallback),
     * evaluates federation trust, and verifies the credential signature against
     * the `vc_issuer.jwks` keys in the entity configuration.
     */
    suspend fun verifyCredentialIssuer(
        credentialJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long? = null
    ): FederationResult<CredentialIssuerResult>

    /**
     * Validate that a federation entity's metadata conforms to the DIIP profile.
     *
     * Checks: federation_entity.display_name, entity type metadata presence,
     * credential_issuer matches entity ID, vc_issuer signing keys present.
     */
    suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String? = null,
        currentTime: Long? = null
    ): FederationResult<FederationEntityMetadataValidationResult>
}

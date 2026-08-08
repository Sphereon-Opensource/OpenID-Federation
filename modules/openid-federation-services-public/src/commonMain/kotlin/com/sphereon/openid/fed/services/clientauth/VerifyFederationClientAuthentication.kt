package com.sphereon.openid.fed.services.clientauth

import com.sphereon.openid.fed.core.config.FederationClientAuthMembershipPolicy
import com.sphereon.openid.fed.core.error.FederationResult

/**
 * Verify OIDFed 1.1 §8.8 `private_key_jwt` client authentication against the federation graph.
 *
 * Mirrors IDK `VerifyClientAuthenticationCommand` for PrivateKeyJwt, but:
 * - identity source is [FederationParticipatingEntityResolver] (not OAuth ClientRegistry)
 * - audience is the host Entity Identifier only
 */
interface VerifyFederationClientAuthentication {
    suspend fun verify(args: VerifyFederationClientAuthenticationArgs): FederationResult<VerifiedFederationClientAuthentication>
}

data class VerifyFederationClientAuthenticationArgs(
    /** Compact JWT client assertion (RFC 7523). */
    val assertionJwt: String,
    /**
     * Client Entity Identifier hint (`client_id` form field or assertion `sub` before verify).
     * Final accepted id must equal assertion `iss` and `sub`.
     */
    val clientEntityId: String,
    /** Host Entity Identifier — required sole `aud` value (OIDFed §8.8). */
    val audienceEntityId: String,
    val hostTenantId: String,
    val hostEntityId: String,
    val allowedSigningAlgs: List<String>,
    val membershipPolicy: FederationClientAuthMembershipPolicy,
    val trustAnchors: List<String> = emptyList(),
)

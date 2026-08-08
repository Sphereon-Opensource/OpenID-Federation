package com.sphereon.openid.fed.services.clientauth

import com.sphereon.openid.fed.core.config.FederationClientAuthMembershipPolicy
import com.sphereon.openid.fed.openapi.models.Jwk

/**
 * A federation participant resolved for endpoint client authentication (OIDFed 1.1 §8.8).
 *
 * This is the federation analogue of an OAuth AS registered client: identity is the
 * Entity Identifier, credentials are Federation Entity Keys from the Entity Configuration.
 */
data class FederationParticipatingEntity(
    val entityId: String,
    val federationEntityKeys: List<Jwk>,
    val membership: FederationMembershipEvidence,
)

/**
 * How the client was accepted as a participant of this federation for §8.8 auth.
 */
sealed class FederationMembershipEvidence {
    /** EC was fetchable; no graph membership enforced. */
    data object AnyFetchable : FederationMembershipEvidence()

    /** Immediate Subordinate of the host tenant (local authority registry). */
    data class ImmediateSubordinate(val hostTenantId: String) : FederationMembershipEvidence()

    /** Trust Chain resolved to a Trust Anchor. */
    data class TrustChainToAnchor(
        val trustAnchor: String,
        val chainStatementCount: Int,
    ) : FederationMembershipEvidence()
}

/**
 * Successful §8.8 private_key_jwt authentication of a federation entity.
 */
data class VerifiedFederationClientAuthentication(
    val clientEntityId: String,
    val entity: FederationParticipatingEntity,
    val membershipPolicy: FederationClientAuthMembershipPolicy,
)

/**
 * Inputs for resolving a client Entity Identifier for endpoint authentication.
 */
data class ResolveParticipatingEntityArgs(
    val clientEntityId: String,
    /** Tenant that owns the endpoint being called. */
    val hostTenantId: String,
    /** Entity Identifier of the endpoint host (also default Trust Anchor). */
    val hostEntityId: String,
    val policy: FederationClientAuthMembershipPolicy,
    /**
     * Trust Anchors for [FederationClientAuthMembershipPolicy.TRUST_CHAIN_TO_TA] / [HYBRID].
     * Empty → [hostEntityId] only.
     */
    val trustAnchors: List<String> = emptyList(),
)

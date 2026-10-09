package com.sphereon.openid.fed.services.command.trust

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.services.command.registration.RegistrationTrustAnchor
import kotlinx.serialization.Serializable

/**
 * Whether an Entity is trusted under one Trust Anchor: its Trust Chain to that anchor resolves and verifies with the
 * keys the anchor publishes at its own Entity Identifier (or pinned keys, when configured), and it holds every
 * required Trust Mark, each valid under that anchor (OpenID Federation 1.1 §4, §7.3, §10).
 */
data class VerifyEntityTrustArgs(
    val entityIdentifier: String,
    val trustAnchor: RegistrationTrustAnchor,
    /** Trust Mark types the Entity must hold, verified under [trustAnchor]. */
    val requiredTrustMarkTypes: List<String> = emptyList(),
    /**
     * When set, the chain must run through this Immediate Superior: resolution starts at it, and the second
     * statement of the chain must be the Subordinate Statement it issued about the Entity.
     */
    val viaSuperior: String? = null,
)

@Serializable
data class VerifiedEntityTrust(
    val entityIdentifier: String,
    val trustAnchor: String,
    val trustChain: List<String>,
    /** The issuers of the chain statements, from the Entity to the Trust Anchor. */
    val chainPath: List<String>,
    val verifiedTrustMarkTypes: List<String>,
    /** The earliest expiry of the chain and of the verified Trust Marks. */
    val validUntilEpochSeconds: Long,
)

interface VerifyEntityTrustCommand : ServiceCommand<VerifyEntityTrustArgs, VerifiedEntityTrust, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust.verify-entity"
    }
}

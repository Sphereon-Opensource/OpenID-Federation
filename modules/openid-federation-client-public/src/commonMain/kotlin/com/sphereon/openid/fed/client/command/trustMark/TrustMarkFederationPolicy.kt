package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement

/**
 * How a Trust Mark type is recognized by a federation Trust Anchor (OIDFed 1.1 §3.1.2 / §7).
 *
 * Cross-federation: an Entity may publish Trust Marks in its Entity Configuration that are
 * only meaningful in other federations. When evaluating trust under a given Trust Anchor,
 * types that are [NOT_RECOGNIZED] MUST be filtered out for that federation (not treated as
 * proof of compliance, and not as a reason to reject the entity).
 */
enum class TrustMarkRecognition {
    /** Type listed in `trust_mark_owners` — delegation required. */
    OWNER_DELEGATION,

    /** Type listed in `trust_mark_issuers` with a non-empty issuer allow-list. */
    AUTHORIZED_ISSUERS,

    /**
     * Type listed in `trust_mark_issuers` with an empty issuer array —
     * anyone may issue Trust Marks of this type (OIDFed 1.1 §3.1.2).
     */
    ANYONE_MAY_ISSUE,

    /**
     * Type is not declared on this Trust Anchor — not applicable to this federation.
     * Callers should filter the mark out when resolving/evaluating under this TA.
     */
    NOT_RECOGNIZED
}

/**
 * Federation-scoped Trust Mark policy helpers.
 */
object TrustMarkFederationPolicy {

    /**
     * Determine whether [trustMarkType] is recognized by [trustAnchorConfig] for the
     * federation rooted at that Trust Anchor.
     *
     * Recognition uses only TA claims (`trust_mark_owners` / `trust_mark_issuers`).
     * It does not validate the Trust Mark JWT itself.
     */
    fun recognize(
        trustMarkType: String,
        trustAnchorConfig: EntityConfigurationStatement
    ): TrustMarkRecognition {
        val owners = trustAnchorConfig.trustMarkOwners
        if (owners != null && owners.containsKey(trustMarkType)) {
            return TrustMarkRecognition.OWNER_DELEGATION
        }

        val issuers = trustAnchorConfig.trustMarkIssuers
        if (issuers != null && issuers.containsKey(trustMarkType)) {
            val allowed = issuers[trustMarkType]
            return if (allowed != null && allowed.isEmpty()) {
                TrustMarkRecognition.ANYONE_MAY_ISSUE
            } else {
                TrustMarkRecognition.AUTHORIZED_ISSUERS
            }
        }

        return TrustMarkRecognition.NOT_RECOGNIZED
    }

    /**
     * Whether an issuer is allowed under [recognition] given the TA's issuer list for the type.
     */
    fun isIssuerAuthorized(
        recognition: TrustMarkRecognition,
        trustMarkType: String,
        issuer: String,
        trustAnchorConfig: EntityConfigurationStatement
    ): Boolean {
        return when (recognition) {
            TrustMarkRecognition.ANYONE_MAY_ISSUE -> true
            TrustMarkRecognition.AUTHORIZED_ISSUERS -> {
                val allowed = trustAnchorConfig.trustMarkIssuers?.get(trustMarkType) ?: return false
                allowed.contains(issuer)
            }
            // Owner path: issuer is the delegate; authorization is via valid delegation JWT
            TrustMarkRecognition.OWNER_DELEGATION -> true
            TrustMarkRecognition.NOT_RECOGNIZED -> false
        }
    }
}

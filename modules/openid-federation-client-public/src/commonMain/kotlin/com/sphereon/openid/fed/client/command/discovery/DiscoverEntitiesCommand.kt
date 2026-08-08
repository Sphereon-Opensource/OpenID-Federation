package com.sphereon.openid.fed.client.command.discovery

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.core.error.FederationError

/**
 * Top-down federation entity discovery via list endpoints (wallet profile §8.3 pattern).
 *
 * Starting from [startEntityId] (typically a Trust Anchor or Intermediate), walks
 * `federation_list_endpoint` listings, optionally descending into Intermediates.
 */
data class DiscoverEntitiesArgs(
    /** Entity Identifier to start listing from (usually a Trust Anchor). */
    val startEntityId: String,
    /**
     * When non-null, only collect subordinates listed with this `entity_type`
     * (e.g. `openid_credential_issuer`, `openid_credential_verifier`).
     */
    val entityType: String? = null,
    /** Walk Intermediate list endpoints recursively (wallet §8.3 full browse). */
    val recursive: Boolean = true,
    /** Max Intermediate depth (TA = 0). */
    val maxDepth: Int = 5,
    /**
     * When true, only include entities that resolve a Trust Chain to one of [trustAnchors]
     * (non-revocation / membership check).
     */
    val verifyTrust: Boolean = false,
    /** Trust Anchors used when [verifyTrust] is true (preference order). */
    val trustAnchors: Array<String> = emptyArray(),
    val maxDepthTrustChain: Int = 5,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoverEntitiesArgs) return false
        return startEntityId == other.startEntityId &&
            entityType == other.entityType &&
            recursive == other.recursive &&
            maxDepth == other.maxDepth &&
            verifyTrust == other.verifyTrust &&
            trustAnchors.contentEquals(other.trustAnchors) &&
            maxDepthTrustChain == other.maxDepthTrustChain
    }

    override fun hashCode(): Int {
        var r = startEntityId.hashCode()
        r = 31 * r + (entityType?.hashCode() ?: 0)
        r = 31 * r + recursive.hashCode()
        r = 31 * r + maxDepth
        r = 31 * r + verifyTrust.hashCode()
        r = 31 * r + trustAnchors.contentHashCode()
        r = 31 * r + maxDepthTrustChain
        return r
    }
}

data class DiscoveredEntity(
    val entityIdentifier: String,
    /** Superior whose list endpoint returned this entity. */
    val discoveredVia: String,
    /**
     * When discovery ran with [DiscoverEntitiesArgs.verifyTrust], whether a Trust Chain
     * to a configured Trust Anchor was established. Null if trust was not verified.
     */
    val trusted: Boolean? = null,
    val trustChain: List<String>? = null,
    val trustAnchor: String? = null,
)

data class DiscoverEntitiesResult(
    val startEntityId: String,
    val entityType: String?,
    val entities: List<DiscoveredEntity>,
    /** Superiors whose list endpoint was successfully queried. */
    val listedSuperiors: List<String>,
    /** Non-fatal per-entity or per-superior errors (listing continues). */
    val warnings: List<String> = emptyList(),
)

interface DiscoverEntitiesCommandService {
    suspend fun discoverEntities(args: DiscoverEntitiesArgs): IdkResult<DiscoverEntitiesResult, FederationError>
}

interface DiscoverEntitiesCommand :
    Command<DiscoverEntitiesArgs, DiscoverEntitiesResult, FederationError>,
    DiscoverEntitiesCommandService {

    companion object {
        const val COMMAND_ID = "fed.client.discover-entities"
    }
}

package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.session.Command
import com.sphereon.openid.fed.client.command.discovery.DiscoveredEntity
import com.sphereon.openid.fed.core.error.FederationError

/**
 * Wallet profile §8.3: browse the federation for Credential Issuers via list endpoints.
 */
data class DiscoverCredentialIssuersArgs(
    /** Trust Anchors (preference order); first is also the default listing start. */
    val trustAnchors: Array<String>,
    /**
     * Superior to start listing from. Defaults to the first Trust Anchor when null.
     */
    val startEntityId: String? = null,
    val recursive: Boolean = true,
    val maxDepth: Int = 5,
    /**
     * When true (default), only return CIs with a valid Trust Chain (non-revoked / still member).
     */
    val verifyTrust: Boolean = true,
    val maxDepthTrustChain: Int = 5,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoverCredentialIssuersArgs) return false
        return trustAnchors.contentEquals(other.trustAnchors) &&
            startEntityId == other.startEntityId &&
            recursive == other.recursive &&
            maxDepth == other.maxDepth &&
            verifyTrust == other.verifyTrust &&
            maxDepthTrustChain == other.maxDepthTrustChain
    }

    override fun hashCode(): Int {
        var r = trustAnchors.contentHashCode()
        r = 31 * r + (startEntityId?.hashCode() ?: 0)
        r = 31 * r + recursive.hashCode()
        r = 31 * r + maxDepth
        r = 31 * r + verifyTrust.hashCode()
        r = 31 * r + maxDepthTrustChain
        return r
    }
}

data class DiscoverCredentialIssuersResult(
    val startEntityId: String,
    val credentialIssuers: List<DiscoveredEntity>,
    val listedSuperiors: List<String>,
    val warnings: List<String> = emptyList(),
)

interface DiscoverCredentialIssuersCommandService {
    suspend fun discoverCredentialIssuers(
        trustAnchors: Array<String>,
        startEntityId: String? = null,
        recursive: Boolean = true,
        maxDepth: Int = 5,
        verifyTrust: Boolean = true,
        maxDepthTrustChain: Int = 5,
    ): IdkResult<DiscoverCredentialIssuersResult, FederationError>
}

interface DiscoverCredentialIssuersCommand :
    Command<DiscoverCredentialIssuersArgs, DiscoverCredentialIssuersResult, FederationError>,
    DiscoverCredentialIssuersCommandService {

    companion object {
        const val COMMAND_ID = "fed.wallet.discover-credential-issuers"
        const val ENTITY_TYPE_CREDENTIAL_ISSUER = "openid_credential_issuer"
        const val ENTITY_TYPE_WALLET_PROVIDER = "openid_wallet_provider"
    }
}

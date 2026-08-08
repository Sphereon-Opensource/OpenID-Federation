package com.sphereon.openid.fed.common.builder

import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata

/**
 * Builds [FederationEntityMetadata] endpoint URLs from an entity identifier.
 *
 * Authority entities (Trust Anchor / Intermediate) get fetch + list.
 * Trust Mark issuers may publish status/list/endpoint without being an authority.
 */
class FederationEntityMetadataObjectBuilder {
    private var identifier: String? = null
    private var authorityEndpoints: Boolean = true
    private var trustMarkEndpoints: Boolean = true
    private var resolveEndpoint: Boolean = true
    private var historicalKeysEndpoint: Boolean = true
    private var endpointAuthSigningAlgValuesSupported: List<String>? = null

    fun identifier(identifier: String) = apply { this.identifier = identifier }

    /** When false, omits federation_fetch_endpoint and federation_list_endpoint (leaf-safe). */
    fun authorityEndpoints(enabled: Boolean) = apply { this.authorityEndpoints = enabled }

    fun trustMarkEndpoints(enabled: Boolean) = apply { this.trustMarkEndpoints = enabled }

    fun resolveEndpoint(enabled: Boolean) = apply { this.resolveEndpoint = enabled }

    fun historicalKeysEndpoint(enabled: Boolean) = apply { this.historicalKeysEndpoint = enabled }

    fun endpointAuthSigningAlgValuesSupported(algs: List<String>?) = apply {
        this.endpointAuthSigningAlgValuesSupported = algs
    }

    fun build(): FederationEntityMetadata {
        val id = identifier
            ?: throw IllegalArgumentException("identifier must be provided")

        return FederationEntityMetadata(
            federationListEndpoint = if (authorityEndpoints) FederationEndpointUrls.list(id) else null,
            federationFetchEndpoint = if (authorityEndpoints) FederationEndpointUrls.fetch(id) else null,
            federationTrustMarkStatusEndpoint = if (trustMarkEndpoints) FederationEndpointUrls.trustMarkStatus(id) else null,
            federationTrustMarkListEndpoint = if (trustMarkEndpoints) FederationEndpointUrls.trustMarkList(id) else null,
            federationTrustMarkEndpoint = if (trustMarkEndpoints) FederationEndpointUrls.trustMark(id) else null,
            federationHistoricalKeysEndpoint = if (historicalKeysEndpoint) FederationEndpointUrls.historicalKeys(id) else null,
            federationResolveEndpoint = if (resolveEndpoint) FederationEndpointUrls.resolve(id) else null,
            endpointAuthSigningAlgValuesSupported = endpointAuthSigningAlgValuesSupported
        )
    }
}

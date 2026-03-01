package com.sphereon.openid.fed.common.builder

import com.sphereon.openid.fed.openapi.models.FederationEntityMetadata

class FederationEntityMetadataObjectBuilder {
    private var identifier: String? = null
    private var endpointAuthSigningAlgValuesSupported: List<String>? = null

    fun identifier(identifier: String) = apply { this.identifier = identifier }

    fun endpointAuthSigningAlgValuesSupported(algs: List<String>) = apply {
        this.endpointAuthSigningAlgValuesSupported = algs
    }

    fun build(): FederationEntityMetadata {
        return FederationEntityMetadata(
            federationListEndpoint = "${identifier}/list",
            federationFetchEndpoint = "${identifier}/fetch",
            federationTrustMarkStatusEndpoint = "${identifier}/trust-mark-status",
            federationTrustMarkListEndpoint = "${identifier}/trust-mark-list",
            federationTrustMarkEndpoint = "${identifier}/trust-mark",
            federationHistoricalKeysEndpoint = "${identifier}/historical-keys",
            federationResolveEndpoint = "${identifier}/resolve",
            endpointAuthSigningAlgValuesSupported = endpointAuthSigningAlgValuesSupported
        )
    }
}

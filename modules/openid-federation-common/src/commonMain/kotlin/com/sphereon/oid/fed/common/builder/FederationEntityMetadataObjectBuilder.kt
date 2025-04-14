package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata

class FederationEntityMetadataObjectBuilder {
    private var identifier: String? = null

    fun identifier(identifier: String) = apply { this.identifier = identifier }

    fun build(): FederationEntityMetadata {
        return FederationEntityMetadata(
            federationListEndpoint = "${identifier}/list",
            federationFetchEndpoint = "${identifier}/fetch",
            federationTrustMarkStatusEndpoint = "${identifier}/trust-mark-status",
            federationTrustMarkListEndpoint = "${identifier}/trust-mark-list",
            federationTrustMarkEndpoint = "${identifier}/trust-mark",
            federationHistoricalKeysEndpoint = "${identifier}/historical-keys",
            federationResolveEndpoint = "${identifier}/resolve"
        )
    }
}

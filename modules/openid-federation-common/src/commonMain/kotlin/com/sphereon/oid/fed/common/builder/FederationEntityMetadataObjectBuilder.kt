package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata

class FederationEntityMetadataObjectBuilder {
    private var identifier: String? = null

    fun identifier(identifier: String) = apply { this.identifier = identifier }

    fun build(): FederationEntityMetadata {
        return FederationEntityMetadata(
            federationListEndpoint = "${identifier}/list",
            federationFetchEndpoint = "${identifier}/fetch",
            federationTrustMarkStatusEndpoint = "${identifier}/trust_mark_status",
            federationTrustMarkListEndpoint = "${identifier}/trust_mark_list",
            federationTrustMarkEndpoint = "${identifier}/trust_mark"
        )
    }
}

package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata

class FederationEntityMetadataBuilder {
    private var identifier: String? = null

    fun identifier(identifier: String) = apply { this.identifier = identifier }

    fun build(): FederationEntityMetadata {
        return FederationEntityMetadata(
            federationListEndpoint = "${identifier}/list",
            federationFetchEndpoint = "${identifier}/fetch"
        )
    }
}

package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer

fun TrustMarkIssuer.toDTO(): com.sphereon.oid.fed.openapi.models.TrustMarkIssuer {
    return com.sphereon.oid.fed.openapi.models.TrustMarkIssuer(
        id = this.id,
        trustMarkTypeId = this.trust_mark_type_id,
        issuer = this.issuer_identifier,
        createdAt = this.created_at.toString(),
        deletedAt = this.deleted_at?.toString()
    )
}


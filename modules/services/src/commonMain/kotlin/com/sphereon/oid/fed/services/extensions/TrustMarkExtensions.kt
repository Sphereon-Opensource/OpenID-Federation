package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.TrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeDTO
import com.sphereon.oid.fed.persistence.models.TrustMark
import com.sphereon.oid.fed.persistence.models.TrustMarkType

fun TrustMarkType.toTrustMarkTypeDTO(): TrustMarkTypeDTO {
    return TrustMarkTypeDTO(
        id = this.id,
        identifier = this.identifier,
        name = this.name,
        description = this.description,
        createdAt = this.created_at.toString(),
        updatedAt = this.updated_at?.toString()
    )
}

fun TrustMark.toTrustMarkDTO(): TrustMarkDTO {
    return TrustMarkDTO(
        id = this.id,
        accountId = this.account_id,
        sub = this.sub,
        trustMarkTypeIdentifier = this.trust_mark_type_identifier,
        iat = this.iat,
        exp = this.exp,
        createdAt = this.created_at.toString()
    )
}

package com.sphereon.oid.fed.services.mappers.trustMark

import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMark
import com.sphereon.oid.fed.persistence.models.TrustMark as TrustMarkEntity
import com.sphereon.oid.fed.persistence.models.TrustMarkType as TrustMarkTypeEntity

fun TrustMarkTypeEntity.toDTO(): TrustMarkType {
    return TrustMarkType(
        id = this.id,
        identifier = this.identifier,
        createdAt = this.created_at.toString(),
        updatedAt = this.updated_at?.toString()
    )
}

fun TrustMarkEntity.toDTO(): TrustMark {
    return TrustMark(
        id = this.trust_mark_type_identifier,
        trustMark = this.trust_mark_value
    )
}

fun ReceivedTrustMark.toTrustMark(): TrustMark {
    return TrustMark(
        id = this.trust_mark_type_identifier,
        trustMark = this.jwt
    )
}
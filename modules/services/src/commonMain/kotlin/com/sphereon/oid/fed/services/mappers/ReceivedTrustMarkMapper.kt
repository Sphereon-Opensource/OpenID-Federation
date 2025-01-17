package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMark

fun ReceivedTrustMark.toReceivedTrustMarkDTO(): ReceivedTrustMarkDTO {
    return ReceivedTrustMarkDTO(
        id = this.id,
        accountId = this.account_id,
        trustMarkTypeIdentifier = this.trust_mark_type_identifier,
        jwt = this.jwt,
        createdAt = this.created_at.toString(),
    )
}

fun ReceivedTrustMark.toTrustMark(): TrustMark {
    return TrustMark(
        id = this.trust_mark_type_identifier,
        trustMark = this.jwt
    )
}

package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMark

fun ReceivedTrustMark.toReceivedTrustMarkDTO(): com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO {
    return com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO(
        id = this.id,
        accountId = this.account_id,
        trustMarkTypeIdentifier = this.trust_mark_type_id,
        jwt = this.jwt,
        createdAt = this.created_at.toString(),
    )
}

fun ReceivedTrustMark.toTrustMark(): TrustMark {
    return TrustMark(
        id = this.trust_mark_type_id,
        trustMark = this.jwt
    )
}

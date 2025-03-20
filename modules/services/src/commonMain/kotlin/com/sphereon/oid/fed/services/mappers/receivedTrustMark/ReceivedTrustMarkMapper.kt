package com.sphereon.oid.fed.services.mappers.receivedTrustMark

import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarksResponse
import com.sphereon.oid.fed.persistence.models.ReceivedTrustMark as ReceivedTrustMarkEntity

fun ReceivedTrustMarkEntity.toDTO(): ReceivedTrustMark {
    return ReceivedTrustMark(
        id = this.id,
        accountId = this.account_id,
        trustMarkTypeIdentifier = this.trust_mark_type_identifier,
        jwt = this.jwt,
        createdAt = this.created_at?.toString()
    )
}


fun Array<ReceivedTrustMark>.toReceivedTrustMarksResponse() = ReceivedTrustMarksResponse(this)
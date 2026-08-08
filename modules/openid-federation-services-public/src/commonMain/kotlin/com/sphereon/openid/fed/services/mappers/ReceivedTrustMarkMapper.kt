package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMarksResponse
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMark as ReceivedTrustMarkEntity

fun ReceivedTrustMarkEntity.toDTO(): ReceivedTrustMark {
    return ReceivedTrustMark(
        id = this.id,
        accountId = this.account_id,
        trustMarkType = this.trust_mark_id,
        jwt = this.jwt,
        createdAt = this.created_at?.toString()
    )
}


fun Array<ReceivedTrustMark>.toReceivedTrustMarksResponse() = ReceivedTrustMarksResponse(this.toList())

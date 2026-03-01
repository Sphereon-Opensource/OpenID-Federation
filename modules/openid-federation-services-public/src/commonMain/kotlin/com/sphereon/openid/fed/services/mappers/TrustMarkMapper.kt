package com.sphereon.openid.fed.services.mappers


import com.sphereon.openid.fed.openapi.models.*
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity
import com.sphereon.openid.fed.persistence.models.TrustMarkType as TrustMarkTypeEntity

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
        trustMarkType = this.trust_mark_id,
        trustMark = this.trust_mark_value,
    )
}

fun TrustMarkEntity.toCreateTrustMarkResult(): CreateTrustMarkResult {
    return CreateTrustMarkResult(
        id = this.id,
        accountId = this.account_id,
        trustMarkType = this.trust_mark_id,
        sub = this.sub,
        trustMarkValue = this.trust_mark_value,
        iat = this.iat.toInt(),
        exp = this.exp?.toInt()
    )
}

fun ReceivedTrustMark.toTrustMark(): TrustMark {
    return TrustMark(
        trustMarkType = this.trust_mark_id,
        trustMark = this.jwt
    )
}

fun List<TrustMark>.toTrustMarksResponse() = TrustMarksResponse(this)

fun List<TrustMarkType>.toTrustMarkTypesResponse() = TrustMarkTypesResponse(this)

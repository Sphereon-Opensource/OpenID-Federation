package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateJwksResponse
import com.sphereon.openid.fed.persistence.models.SubordinateJwk as SubordinateJwkEntity


fun SubordinateJwkEntity.toDTO(): SubordinateJwk {
    return SubordinateJwk(
        id = id,
        subordinateId = subordinate_id,
        key = toJwk(),
        createdAt = created_at.toString()
    )
}

fun SubordinateJwkEntity.toJwk(): Jwk {
    return jsonSerialization.decodeFromString<Jwk>(this.key)
}

fun Jwk.toJsonString(): String = jsonSerialization.encodeToString(this)

fun Array<SubordinateJwk>.toSubordinateJwksResponse() = SubordinateJwksResponse(this.toList())

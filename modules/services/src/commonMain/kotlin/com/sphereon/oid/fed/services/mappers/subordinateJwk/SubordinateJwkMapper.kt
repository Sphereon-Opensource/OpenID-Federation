package com.sphereon.oid.fed.services.mappers.subordinateJwk

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwksResponse
import com.sphereon.oid.fed.services.mappers.jsonSerialization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.sphereon.oid.fed.persistence.models.SubordinateJwk as SubordinateJwkEntity


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

fun Array<SubordinateJwk>.toSubordinateJwksResponse() = SubordinateJwksResponse(this)
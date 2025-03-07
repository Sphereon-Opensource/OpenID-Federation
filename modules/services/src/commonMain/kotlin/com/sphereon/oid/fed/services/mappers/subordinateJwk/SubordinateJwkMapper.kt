package com.sphereon.oid.fed.services.mappers.subordinateJwk

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.sphereon.oid.fed.persistence.models.SubordinateJwk as SubordinateJwkEntity

private val json = Json {
    ignoreUnknownKeys = true
}

fun SubordinateJwkEntity.toDTO(): SubordinateJwk {
    return SubordinateJwk(
        id = id,
        subordinateId = subordinate_id,
        key = json.decodeFromString<JsonObject>(this.key),
        createdAt = created_at?.toString()
    )
}

fun SubordinateJwkEntity.toJwk(): Jwk {
    return json.decodeFromString<Jwk>(this.key)
}
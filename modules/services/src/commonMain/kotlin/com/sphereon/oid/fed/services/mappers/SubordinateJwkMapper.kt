package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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

fun SubordinateJwk.toJwk(): Jwk {
    val jsonKey = key ?: throw IllegalArgumentException("SubordinateJwk.key cannot be null")
    return json.decodeFromJsonElement<Jwk>(jsonKey)
}

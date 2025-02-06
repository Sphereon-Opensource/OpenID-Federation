package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import kotlinx.serialization.json.Json
import com.sphereon.oid.fed.persistence.models.SubordinateJwk as SubordinateJwkEntity

private val json = Json {
    ignoreUnknownKeys = true
}

fun SubordinateJwkEntity.toDTO(): SubordinateJwk {
    return json.decodeFromString<SubordinateJwk>(this.key)
}

fun SubordinateJwkEntity.toJwk(): Jwk {
    return json.decodeFromString<Jwk>(this.key)
}

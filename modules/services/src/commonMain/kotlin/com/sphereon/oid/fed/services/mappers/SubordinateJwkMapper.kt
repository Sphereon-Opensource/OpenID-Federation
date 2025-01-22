package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwkDto
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val json = Json {
    ignoreUnknownKeys = true
}

fun SubordinateJwk.toJwk(): Jwk {
    return json.decodeFromString<Jwk>(this.key)
}

fun SubordinateJwk.toSubordinateJwkDto(): SubordinateJwkDto {
    return json.decodeFromString<SubordinateJwkDto>(this.key)
}

fun SubordinateJwk.toSubordinateAdminJwkDTO(): SubordinateJwkDto {
    return SubordinateJwkDto(
        id = this.id,
        subordinateId = this.subordinate_id,
        key = Json.parseToJsonElement(this.key).jsonObject,
        createdAt = this.created_at.toString()
    )
}

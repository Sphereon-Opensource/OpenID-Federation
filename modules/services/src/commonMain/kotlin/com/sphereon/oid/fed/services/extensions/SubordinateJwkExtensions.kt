package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkDTO
import com.sphereon.oid.fed.openapi.models.SubordinateAdminJwkDto
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val json = Json {
    ignoreUnknownKeys = true
}

fun SubordinateJwk.toJwk(): Jwk {
    return json.decodeFromString<Jwk>(this.key)
}

fun SubordinateJwk.toJwkDto(): JwkDTO {
    return json.decodeFromString<JwkDTO>(this.key)
}


fun SubordinateJwk.toSubordinateAdminJwkDTO(): SubordinateAdminJwkDto {
    return SubordinateAdminJwkDto(
        id = this.id,
        subordinateId = this.subordinate_id,
        key = Json.parseToJsonElement(this.key).jsonObject,
        createdAt = this.created_at.toString()
    )
}

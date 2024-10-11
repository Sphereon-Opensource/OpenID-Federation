package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.openapi.models.JwkDTO
import com.sphereon.oid.fed.openapi.models.SubordinateAdminJwkDto
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun SubordinateJwk.toJwkDTO(): JwkDTO {
    val key = Json.decodeFromString<JwkAdminDTO>(this.key)

    return key.toJwkDto()
}

fun SubordinateJwk.toSubordinateAdminJwkDTO(): SubordinateAdminJwkDto {
    return SubordinateAdminJwkDto(
        id = this.id,
        subordinateId = this.subordinate_id,
        key = Json.parseToJsonElement(this.key).jsonObject,
        createdAt = this.created_at.toString()
    )
}

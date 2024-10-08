package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.models.SubordinateJwk
import kotlinx.serialization.json.Json

fun SubordinateJwk.toJwk(): Jwk {
    val key = Json.decodeFromString<JwkAdminDTO>(this.key)

    return key.toJwk()
}

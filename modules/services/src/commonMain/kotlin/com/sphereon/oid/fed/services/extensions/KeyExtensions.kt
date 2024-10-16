package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import kotlinx.serialization.json.Json
import com.sphereon.oid.fed.persistence.models.Jwk as JwkPersistence

fun JwkPersistence.toJwkAdminDTO(): JwkAdminDTO {
    val key = Json.decodeFromString<Jwk>(this.key)

    return JwkAdminDTO(
        e = key.e,
        x = key.x,
        y = key.y,
        n = key.n,
        alg = key.alg,
        crv = key.crv,
        kid = key.kid,
        kty = key.kty,
        use = key.use,
        x5c = key.x5c,
        x5t = key.x5t,
        x5u = key.x5u,
        x5tS256 = key.x5tS256,
    )
}

fun JwkAdminDTO.toJwk(): Jwk {
    return Jwk(
        crv = crv,
        e = e,
        x = x,
        y = y,
        n = n,
        alg = alg,
        kid = kid,
        kty = kty!!,
        use = use,
        x5c = x5c,
        x5t = x5t,
        x5u = x5u,
        x5tS256 = x5tS256,
    )
}

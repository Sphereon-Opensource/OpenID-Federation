package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.EntityJwkRevoked
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import kotlinx.serialization.json.Json
import com.sphereon.oid.fed.persistence.models.Jwk as JwkPersistence

fun JwkPersistence.toJwkAdminDTO(): JwkAdminDTO {
    val key = Json.decodeFromString<Jwk>(this.key)

    return JwkAdminDTO(
        id = this.id,
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
        revokedAt = this.revoked_at.toString(),
        revokedReason = this.revoked_reason,
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

fun JwkAdminDTO.toHistoricalKey(): HistoricalKey {
    return HistoricalKey(
        e = e,
        x = x,
        y = y,
        n = n,
        alg = alg,
        crv = crv,
        kid = kid,
        kty = kty,
        use = use,
        x5c = x5c,
        x5t = x5t,
        x5u = x5u,
        x5tS256 = x5tS256,
        revoked = revokedAt?.let { EntityJwkRevoked(it, revokedReason) }
    )
}

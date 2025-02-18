package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.*
import kotlinx.serialization.json.Json
import com.sphereon.oid.fed.persistence.models.Jwk as JwkEntity
import com.sphereon.oid.fed.openapi.models.EntityJwk

fun JwkEntity.toDTO(): EntityJwk {
    val key = Json.decodeFromString<BaseJwk>(this.key)

    return EntityJwk(
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

fun JwkEntity.toJwk(): Jwk {
    val key = Json.decodeFromString<Jwk>(this.key)

    return Jwk(
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
        x5tS256 = key.x5tS256
    )
}

fun JwkEntity.toHistoricalKey(): HistoricalKey {
    val key = Json.decodeFromString<Jwk>(this.key)

    return HistoricalKey(
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
        revoked = JwkRevoked(
            reason = this.revoked_reason,
            revokedAt = this.revoked_at.toString()
        )
    )
}

fun EntityJwk.toJwk(): Jwk {
    return Jwk(
        e = this.e,
        x = this.x,
        y = this.y,
        n = this.n,
        alg = this.alg,
        crv = this.crv,
        kid = this.kid,
        kty = this.kty,
        use = this.use,
        x5c = this.x5c,
        x5t = this.x5t,
        x5u = this.x5u,
        x5tS256 = this.x5tS256
    )
}
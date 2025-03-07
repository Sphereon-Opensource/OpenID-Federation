package com.sphereon.oid.fed.services.mappers.jwk

import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkRevoked
import kotlinx.serialization.json.Json
import com.sphereon.oid.fed.persistence.models.Jwk as JwkEntity

private val json = Json {
    ignoreUnknownKeys = true
}

fun JwkEntity.toDTO(): AccountJwk {
    val key = json.decodeFromString<Jwk>(this.key)

    return AccountJwk(
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

fun JwkEntity.toHistoricalKey(): HistoricalKey {
    val key = json.decodeFromString<Jwk>(this.key)

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

fun AccountJwk.toJwk(): Jwk {
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

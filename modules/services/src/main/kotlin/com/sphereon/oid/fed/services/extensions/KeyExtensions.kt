package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.JwkDto
import com.sphereon.oid.fed.persistence.models.Jwk

fun Jwk.toJwkDTO(): JwkDto {
    return JwkDto(
        id = this.id,
        accountId = this.account_id,
        uuid = this.uuid.toString(),
        e = this.e,
        n = this.n,
        x = this.x,
        y = this.y,
        alg = this.alg,
        crv = this.crv,
        kid = this.kid,
        kty = this.kty,
        use = this.use,
        x5c = this.x5c as List<String>? ?: null,
        x5t = this.x5t,
        x5u = this.x5u,
        x5tHashS256 = this.x5t_s256,
        createdAt = this.created_at.toString(),
        revokedAt = this.revoked_at.toString(),
        revokedReason = this.revoked_reason
    )
}

package com.sphereon.oid.fed.kms.local.extensions

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey

fun JwkWithPrivateKey.toJwk(): Jwk = Jwk(
    kid = this.kid,
    use = this.use,
    crv = this.crv,
    n = this.n,
    e = this.e,
    x = this.x,
    y = this.y,
    kty = this.kty,
    alg = this.alg,
    x5u = this.x5u,
    x5t = this.x5t,
    x5c = this.x5c,
    x5tS256 = this.x5tS256
)

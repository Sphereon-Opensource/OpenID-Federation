package com.sphereon.oid.fed.kms.local.jwk

import com.sphereon.oid.fed.kms.local.jwt.Jose
import com.sphereon.oid.fed.openapi.models.Jwk

@ExperimentalJsExport
@JsExport
actual fun generateKeyPair(): Jwk {
    val key = Jose.generateKeyPair("EC")
    return Jwk(
        d = key.d,
        alg = key.alg,
        crv = key.crv,
        x = key.x,
        y = key.y,
        kid = key.kid,
        kty = key.kty,
        use = key.use,
    )
}

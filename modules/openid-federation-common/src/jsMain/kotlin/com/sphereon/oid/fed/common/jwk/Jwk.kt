package com.sphereon.oid.fed.common.jwk

import com.sphereon.oid.fed.common.jwt.Jose
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

@ExperimentalJsExport
@JsExport
fun convertToJwk(keyPair: dynamic): Jwk {
    val privateJWK = Jose.exportJWK(keyPair.privateKey)
    val publicJWK = Jose.exportJWK(keyPair.publicKey)
    return Jwk(
        crv = privateJWK.crv,
        d = privateJWK.d,
        kty = privateJWK.kty,
        x = privateJWK.x,
        y = privateJWK.y,
        alg = publicJWK.alg,
        kid = publicJWK.kid,
        use = publicJWK.use,
        x5c = publicJWK.x5c,
        x5t = publicJWK.x5t,
        x5tS256 = privateJWK.x5tS256,
        x5u = publicJWK.x5u,
        dp = privateJWK.dp,
        dq = privateJWK.dq,
        e = privateJWK.e,
        n = privateJWK.n,
        p = privateJWK.p,
        q = privateJWK.q,
        qi = privateJWK.qi
    )
}

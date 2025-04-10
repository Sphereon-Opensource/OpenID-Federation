package com.sphereon.oid.fed.server.admin.mappers

import com.sphereon.oid.fed.openapi.java.models.CreateKey
import com.sphereon.oid.fed.openapi.java.models.Jwk
import com.sphereon.oid.fed.openapi.java.models.SignatureAlgorithm
import com.sphereon.oid.fed.openapi.models.CreateKey as CreateKeyKotlin
import com.sphereon.oid.fed.openapi.models.SignatureAlgorithm as SignatureAlgorithmKotlin

fun Jwk.toKotlin(): com.sphereon.oid.fed.openapi.models.Jwk {
    return com.sphereon.oid.fed.openapi.models.Jwk(
        kty = this.kty,
        kid = this.kid,
        y = this.y,
        x = this.x,
        alg = this.alg,
        e = this.e,
        n = this.n,
        crv = this.crv,
        use = this.use,
        x5c = this.x5c?.toTypedArray(),
        x5t = this.x5t,
        x5u = this.x5t,
        x5tS256 = this.x5tS256
    )
}

fun CreateKey.toKotlin(): CreateKeyKotlin {
    return CreateKeyKotlin(
        kmsKeyRef = kmsKeyRef,
        signatureAlgorithm = signatureAlgorithm?.toKotlin(),
        kms = kms
    )
}

fun SignatureAlgorithm.toKotlin(): SignatureAlgorithmKotlin {
    return SignatureAlgorithmKotlin.valueOf(name)
}

package com.sphereon.oid.fed.services.mappers.jwk

import com.sphereon.crypto.jose.Jwk
import com.sphereon.crypto.jose.Jwk.Companion.serializer
import com.sphereon.json.cryptoJsonSerializer
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.AccountJwksResponse
import com.sphereon.oid.fed.openapi.models.HistoricalKey
import com.sphereon.oid.fed.openapi.models.JwkRevoked
import com.sphereon.oid.fed.openapi.models.Jwk as JwkDto
import com.sphereon.oid.fed.persistence.models.Jwk as JwkEntity

fun JwkEntity.toDTO(): AccountJwk {
    val key: Jwk = cryptoJsonSerializer.decodeFromString<Jwk>(serializer(), this.key)

    return AccountJwk(
        id = this.id,
        kms = this.kms,
        kmsKeyRef = this.kms_key_ref,
        e = key.e,
        x = key.x,
        y = key.y,
        n = key.n,
        alg = key.getSignatureAlgorithm()?.jose?.value,
        crv = key.crv?.value,
        kid = key.getKidAsString(false)!!,
        kty = key.getKty().jose.value,
        use = key.use,
        x5c = key.x5c,
        x5t = key.x5t,
        x5u = key.x5u,
        x5tS256 = key.x5t_S256,
        revokedAt = this.revoked_at?.toString(),
        revokedReason = this.revoked_reason,
    )
}

fun JwkEntity.toHistoricalKey(): HistoricalKey {
    val key: Jwk = cryptoJsonSerializer.decodeFromString(this.key)

    return HistoricalKey(
        e = key.e,
        x = key.x,
        y = key.y,
        n = key.n,
        alg = key.getSignatureAlgorithm()?.jose?.value,
        crv = key.crv?.value,
        kid = key.getKidAsString(false)!!,
        kty = key.getKty().jose.value,
        use = key.use,
        x5c = key.x5c,
        x5t = key.x5t,
        x5u = key.x5u,
        x5tS256 = key.x5t_S256,
        revoked = JwkRevoked(
            reason = this.revoked_reason,
            revokedAt = this.revoked_at.toString()
        )
    )
}

fun AccountJwk.toJwk(): JwkDto {
    return JwkDto(
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

fun  Array<AccountJwk>.toAccountJwksResponse() = AccountJwksResponse(this)
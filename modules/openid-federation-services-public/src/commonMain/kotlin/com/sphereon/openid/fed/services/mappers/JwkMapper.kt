package com.sphereon.openid.fed.services.mappers

import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.crypto.core.jose.Jwk.Companion.serializer
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.openapi.models.TenantJwksResponse
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import com.sphereon.openid.fed.openapi.models.JwkRevoked
import com.sphereon.openid.fed.openapi.models.Jwk as JwkDto
import com.sphereon.openid.fed.persistence.models.Jwk as JwkEntity
import java.time.LocalDateTime
import java.time.ZoneOffset

fun JwkEntity.toDTO(): TenantJwk {
    val key: Jwk = cryptoJsonSerializer.decodeFromString<Jwk>(serializer(), this.key)

    return TenantJwk(
        id = this.id,
        accountId = this.account_id,
        kms = this.kms,
        kmsKeyRef = this.kms_key_ref,
        e = key.e,
        x = key.x,
        y = key.y,
        n = key.n,
        alg = key.getSignatureAlgorithm()?.jose?.value!!,
        crv = key.crv?.value,
        kid = key.getKeyId(false)!!,
        kty = key.getKeyType().jose.value,
        use = key.use,
        x5c = key.x5c?.asList(),
        x5t = key.x5t,
        x5u = key.x5u,
        x5tS256 = key.x5t_S256,
        revokedAt = this.revoked_at?.toEpochSecondString(),
        revokedReason = this.revoked_reason,
    )
}

/**
 * Map a revoked key row to a federation historical key (OIDFed 1.1 §8.7.2): a previously used key whose REQUIRED
 * [HistoricalKey.exp] is its revocation time, after which it MUST NOT be considered valid, and whose
 * [JwkRevoked.revokedAt] is the same NumericDate. Keys in use are not historical and are not mapped.
 */
fun JwkEntity.toHistoricalKey(): HistoricalKey {
    val key: Jwk = cryptoJsonSerializer.decodeFromString(this.key)
    val iat = this.created_at?.toEpochSecond()
    val revokedAtEpoch = requireNotNull(this.revoked_at?.toEpochSecond()) { "Only revoked keys are historical keys" }
    val exp = revokedAtEpoch

    return HistoricalKey(
        e = key.e,
        x = key.x,
        y = key.y,
        n = key.n,
        alg = key.getSignatureAlgorithm()?.jose?.value!!,
        crv = key.crv?.value,
        kid = key.getKeyId(false)!!,
        kty = key.getKeyType().jose.value,
        use = key.use,
        x5c = key.x5c?.asList(),
        x5t = key.x5t,
        x5u = key.x5u,
        x5tS256 = key.x5t_S256,
        iat = iat?.toDouble(),
        exp = exp.toDouble(),
        revoked = JwkRevoked(
            revokedAt = revokedAtEpoch.toDouble(),
            reason = this.revoked_reason
        )
    )
}

fun TenantJwk.toJwk(): JwkDto {
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

fun Array<TenantJwk>.toTenantJwksResponse() = TenantJwksResponse(this.toList())

/** @deprecated Use [toTenantJwksResponse]; same JSON `{ "jwks": [...] }`. */
@Deprecated(
    message = "Use toTenantJwksResponse",
    replaceWith = ReplaceWith("toTenantJwksResponse()")
)
fun Array<TenantJwk>.toAccountJwksResponse() = toTenantJwksResponse()

private fun LocalDateTime.toEpochSecond(): Long =
    this.toInstant(ZoneOffset.UTC).epochSecond

private fun LocalDateTime.toEpochSecondString(): String =
    toEpochSecond().toString()

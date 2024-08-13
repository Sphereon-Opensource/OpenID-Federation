package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.models.Jwk as JwkPersistence

fun JwkPersistence.toJwkAdminDTO(): JwkAdminDTO = JwkAdminDTO(
    id = id,
    accountId = account_id,
    uuid = uuid.toString(),
    e = e,
    n = n,
    x = x,
    y = y,
    alg = alg,
    crv = crv,
    kid = kid,
    kty = kty,
    use = use,
    x5c = x5c as? List<String> ?: null,
    x5t = x5t,
    x5u = x5u,
    x5tHashS256 = x5t_s256,
    createdAt = created_at.toString(),
    revokedAt = revoked_at.toString(),
    revokedReason = revoked_reason
)

fun Jwk.encrypt(): Jwk {
    if (System.getenv("APP_KEY") == null) return this

    fun String?.encryptOrNull() = this?.let { aesEncrypt(it, System.getenv("APP_KEY")) }

    return copy(
        d = d.encryptOrNull(),
        dq = dq.encryptOrNull(),
        qi = qi.encryptOrNull(),
        dp = dp.encryptOrNull(),
        p = p.encryptOrNull(),
        q = q.encryptOrNull()
    )
}

fun JwkPersistence.decrypt(): JwkPersistence {
    if (System.getenv("APP_KEY") == null) return this

    fun String?.decryptOrNull() = this?.let { aesDecrypt(it, System.getenv("APP_KEY")) }

    return copy(
        d = d.decryptOrNull(),
        dq = dq.decryptOrNull(),
        qi = qi.decryptOrNull(),
        dp = dp.decryptOrNull(),
        p = p.decryptOrNull(),
        q = q.decryptOrNull()
    )
}

expect fun aesEncrypt(data: String, key: String): String
expect fun aesDecrypt(data: String, key: String): String


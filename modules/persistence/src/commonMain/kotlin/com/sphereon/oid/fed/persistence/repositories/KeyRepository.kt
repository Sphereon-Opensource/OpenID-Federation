package com.sphereon.oid.fed.persistence.repositories

import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.persistence.models.KeyQueries
import com.sphereon.oid.fed.persistence.models.Jwk as JwkPersistence

class KeyRepository(private val keyQueries: KeyQueries) {
    fun findById(id: Int): JwkPersistence? {
        return keyQueries.findById(id).executeAsOneOrNull()
    }

    fun create(accountId: Int, jwk: Jwk): JwkPersistence {
        return keyQueries.create(
            account_id = accountId,
            kty = jwk.kty,
            e = jwk.e,
            n = jwk.n,
            x = jwk.x,
            y = jwk.y,
            alg = jwk.alg,
            crv = jwk.crv,
            kid = jwk.kid,
            use = jwk.use,
            x5c = jwk.x5c as Array<String>?,
            x5t = jwk.x5t,
            x5u = jwk.x5u,
            d = jwk.d,
            p = jwk.p,
            q = jwk.q,
            dp = jwk.dp,
            dq = jwk.dq,
            qi = jwk.qi,
            x5t_s256 = jwk.x5tS256
        ).executeAsOne()
    }

    fun findByAccountId(accountId: Int): List<JwkPersistence> {
        return keyQueries.findByAccountId(accountId).executeAsList()
    }

    fun revokeKey(id: Int, reason: String? = null) {
        return keyQueries.revoke(reason, id)
    }
}

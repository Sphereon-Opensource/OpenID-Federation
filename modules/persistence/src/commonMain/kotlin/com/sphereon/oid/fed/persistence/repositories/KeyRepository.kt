package com.sphereon.oid.fed.persistence.repositories

import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.persistence.models.KeyQueries

class KeyRepository(keyQueries: KeyQueries) {
    private val keyQueries = keyQueries

    fun findById(id: Int): Jwk? {
        return keyQueries.findById(id).executeAsOneOrNull()
    }

    fun create(
        accountId: Int,
        kty: String,
        e: String? = null,
        n: String? = null,
        x: String? = null,
        y: String? = null,
        alg: String? = null,
        crv: String? = null,
        kid: String? = null,
        use: String? = null,
        x5c: List<String>? = null,
        x5t: String? = null,
        x5u: String? = null,
        d: String? = null,
        p: String? = null,
        q: String? = null,
        dp: String? = null,
        dq: String? = null,
        qi: String? = null,
        x5ts256: String? = null
    ): Jwk {
        val createdKey = keyQueries.create(
            accountId,
            kty = kty,
            e = e,
            n = n,
            x = x,
            y = y,
            alg = alg,
            crv = crv,
            kid = kid,
            use = use,
            x5c = x5c as Array<String>?,
            x5t = x5t,
            x5u = x5u,
            d = d,
            p = p,
            q = q,
            dp = dp,
            dq = dq,
            qi = qi,
            x5t_s256 = x5ts256
        )

        return createdKey.executeAsOne()
    }

    fun findByAccountId(accountId: Int): List<Jwk> {
        return keyQueries.findByAccountId(accountId).executeAsList()
    }

    fun revokeKey(id: Int, reason: String? = null) {
        return keyQueries.revoke(reason, id)
    }
}

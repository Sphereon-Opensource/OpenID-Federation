package com.sphereon.oid.fed.persistence.repositories

import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.persistence.models.KeyQueries

class KeyRepository(keyQueries: KeyQueries) {
    private val keyQueries = keyQueries

    fun findById(id: Int): Jwk? {
        return keyQueries.findById(id).executeAsOneOrNull()
    }

    fun create(accountId: Int, jwk: String): Jwk {
        return keyQueries.create(accountId, jwk).executeAsOne()
    }

    fun findByAccountId(accountId: Int): List<Jwk> {
        return keyQueries.findByAccountId(accountId).executeAsList()
    }
}

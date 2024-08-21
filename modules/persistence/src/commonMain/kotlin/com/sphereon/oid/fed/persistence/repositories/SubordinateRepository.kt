package com.sphereon.oid.fed.persistence.repositories

import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.persistence.models.SubordinateQueries

class SubordinateRepository(private val subordinateQueries: SubordinateQueries) {
    fun findByAccountId(accountId: Int): List<Subordinate> {
        return subordinateQueries.findByAccountId(accountId).executeAsList()
    }

    fun create(accountId: Int, identifier: String): Subordinate {
        return subordinateQueries.create(account_id = accountId, identifier)
            .executeAsOne()
    }

    fun delete(id: Int) {
        return subordinateQueries.delete(id)
    }
}
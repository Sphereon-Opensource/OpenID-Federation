package com.sphereon.oid.fed.server.federation.services

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.Persistence.subordinateStatementQueries
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.server.federation.Constants


class SubordinateService {
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries

    private fun findSubordinatesByAccount(accountUsername: String): Array<Subordinate> {
        val account = accountQueries.findByUsername(accountUsername).executeAsOne()

        return subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun findSubordinatesByAccountAsArray(accountUsername: String): Array<String> {
        val subordinates = findSubordinatesByAccount(accountUsername)
        return subordinates.map { it.identifier }.toTypedArray()
    }

    fun fetchSubordinateStatement(iss: String, sub: String): String {
        val subordinateStatement = subordinateStatementQueries.findByIssAndSub(iss, sub).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.SUBORDINATE_STATEMENT_NOT_FOUND)

        return subordinateStatement.statement
    }

}

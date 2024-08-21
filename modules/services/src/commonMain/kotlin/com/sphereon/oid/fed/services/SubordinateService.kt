package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Subordinate

class SubordinateService {
    private val accountQueries = Persistence.accountQueries
    private val subordinateQueries = Persistence.subordinateQueries

    fun findSubordinatesByAccount(accountUsername: String): Array<Subordinate> {
        val account = accountQueries.findByUsername(accountUsername).executeAsOne()

        return subordinateQueries.findByAccountId(account.id).executeAsList().toTypedArray()
    }

    fun findSubordinatesByAccountAsArray(accountUsername: String): Array<String> {
        val subordinates = findSubordinatesByAccount(accountUsername)
        return subordinates.map { it.identifier }.toTypedArray()
    }

    fun createSubordinate(accountUsername: String, subordinateDTO: CreateSubordinateDTO): Subordinate {
        val account = accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinateAlreadyExists =
            subordinateQueries.findByAccountIdAndIdentifier(account.id, subordinateDTO.identifier).executeAsList()

        if (subordinateAlreadyExists.isNotEmpty()) {
            throw IllegalArgumentException(Constants.SUBORDINATE_ALREADY_EXISTS)
        }

        return subordinateQueries.create(account.id, subordinateDTO.identifier).executeAsOne()
    }
}

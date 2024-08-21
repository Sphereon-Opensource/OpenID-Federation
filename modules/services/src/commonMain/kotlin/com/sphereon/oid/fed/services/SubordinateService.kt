package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Subordinate

class SubordinateService {
    private val accountRepository = Persistence.accountRepository
    private val subordinateRepository = Persistence.subordinateRepository

    fun findSubordinatesByAccount(accountUsername: String): List<Subordinate> {
        val account = accountRepository.findByUsername(accountUsername)
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        return subordinateRepository.findByAccountId(account.id)
    }

    fun findSubordinatesByAccountAsList(accountUsername: String): List<String> {
        val subordinates = findSubordinatesByAccount(accountUsername)
        return subordinates.map { it.identifier }
    }

    fun createSubordinate(accountUsername: String, subordinateDTO: CreateSubordinateDTO): Subordinate {
        val account = accountRepository.findByUsername(accountUsername)
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val subordinateAlreadyExists = subordinateRepository.findByAccountId(account.id)
            .any { it.identifier == subordinateDTO.identifier }

        if (subordinateAlreadyExists) {
            throw IllegalArgumentException(Constants.SUBORDINATE_ALREADY_EXISTS)
        }

        return subordinateRepository.create(account.id, subordinateDTO.identifier)
    }
}

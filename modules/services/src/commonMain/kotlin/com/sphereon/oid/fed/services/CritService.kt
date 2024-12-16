package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.common.exceptions.EntityAlreadyExistsException
import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.Crit

class CritService {

    fun create(accountUsername: String, claim: String): Crit {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        val critAlreadyExists =
            Persistence.critQueries.findByAccountIdAndClaim(account.id, claim).executeAsOneOrNull()

        if (critAlreadyExists != null) {
            throw EntityAlreadyExistsException(Constants.CRIT_ALREADY_EXISTS)
        }

        return Persistence.critQueries.create(account.id, claim).executeAsOneOrNull()
            ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_CRIT)
    }

    fun delete(accountUsername: String, id: Int): Crit {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw NotFoundException(Constants.ACCOUNT_NOT_FOUND)

        return Persistence.critQueries.deleteByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_CRIT)
    }

    private fun findByAccountId(accountId: Int): Array<Crit> {
        return Persistence.critQueries.findByAccountId(accountId).executeAsList().toTypedArray()
    }

    fun findByAccountUsername(accountUsername: String): Array<Crit> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        return findByAccountId(account.id)
    }
}

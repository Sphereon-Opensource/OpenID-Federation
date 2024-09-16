package com.sphereon.oid.fed.services

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.persistence.models.AuthorityHint

class AuthorityHintService {

    fun createAuthorityHint(accountUsername: String, identifier: String): AuthorityHint {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        val authorityHintAlreadyExists =
            Persistence.authorityHintQueries.findByAccountIdAndIdentifier(account.id, identifier).executeAsOneOrNull()

        if (authorityHintAlreadyExists != null) {
            throw IllegalArgumentException(Constants.AUTHORITY_HINT_ALREADY_EXISTS)
        }

        return Persistence.authorityHintQueries.create(account.id, identifier)
            .executeAsOneOrNull()
            ?: throw IllegalStateException(Constants.FAILED_TO_CREATE_AUTHORITY_HINT)
    }

    fun deleteAuthorityHint(accountUsername: String, id: Int): AuthorityHint {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        Persistence.authorityHintQueries.findByAccountIdAndId(account.id, id).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.AUTHORITY_HINT_NOT_FOUND)

        return Persistence.authorityHintQueries.delete(id).executeAsOneOrNull()
            ?: throw IllegalStateException(Constants.FAILED_TO_DELETE_AUTHORITY_HINT)
    }

    fun findByAccountId(accountId: Int): Array<AuthorityHint> {
        return Persistence.authorityHintQueries.findByAccountId(accountId).executeAsList().toTypedArray()
    }

    fun findByAccountUsername(accountUsername: String): Array<AuthorityHint> {
        val account = Persistence.accountQueries.findByUsername(accountUsername).executeAsOneOrNull()
            ?: throw IllegalArgumentException(Constants.ACCOUNT_NOT_FOUND)

        return findByAccountId(account.id)
    }
}

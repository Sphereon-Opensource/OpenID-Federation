package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountsResponse
import com.sphereon.oid.fed.persistence.models.Account as AccountEntity

fun AccountEntity.toDTO(): Account {
    return Account(
        id = this.id,
        username = this.username,
        identifier = this.identifier
    )
}

fun List<Account>.toAccountsResponse(): AccountsResponse = AccountsResponse(this.toTypedArray())
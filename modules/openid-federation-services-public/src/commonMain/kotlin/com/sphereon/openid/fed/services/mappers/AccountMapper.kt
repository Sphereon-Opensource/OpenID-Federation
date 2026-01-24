package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountsResponse
import com.sphereon.openid.fed.persistence.models.Account as AccountEntity

fun AccountEntity.toDTO(): Account {
    return Account(
        id = this.id,
        username = this.username,
        identifier = this.identifier
    )
}

fun List<Account>.toAccountsResponse(): AccountsResponse = AccountsResponse(this)

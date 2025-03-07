package com.sphereon.oid.fed.services.mappers.account

import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.persistence.models.Account as AccountEntity

fun AccountEntity.toDTO(): Account {
    return Account(
        id = this.id,
        username = this.username,
        identifier = this.identifier
    )
}

package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.persistence.models.Account

fun Account.toAccountDTO(): AccountDTO {
    return AccountDTO(
        id = this.id,
        username = this.username,
        identifier = this.identifier
    )
}

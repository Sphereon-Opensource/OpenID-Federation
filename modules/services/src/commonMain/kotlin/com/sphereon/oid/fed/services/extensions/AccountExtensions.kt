package com.sphereon.oid.fed.services.extensions

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.persistence.models.Account

fun Account.toAccountDTO(): AccountDTO {
    return AccountDTO(
        username = this.username
    )
}

package com.sphereon.oid.fed.persistence.extensions

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oif.fed.persistence.models.Account

fun Account.toDTO(): AccountDTO {
    return AccountDTO(
        username = this.username
    )
}

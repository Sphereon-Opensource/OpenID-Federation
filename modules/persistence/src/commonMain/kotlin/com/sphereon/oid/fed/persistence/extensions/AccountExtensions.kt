package com.sphereon.oid.fed.persistence.extensions

import com.sphereon.oid.fed.openapi.models.AccountDTO
import entities.Account as SqlDelightAccount

fun SqlDelightAccount.toDTO(): AccountDTO {
    return AccountDTO(
        username = this.username
    )
}

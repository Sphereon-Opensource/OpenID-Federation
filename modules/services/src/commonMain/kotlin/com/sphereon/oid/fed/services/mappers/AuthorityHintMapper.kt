package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.AuthorityHintDTO
import com.sphereon.oid.fed.persistence.models.AuthorityHint

fun AuthorityHint.toDTO(): AuthorityHintDTO {
    return AuthorityHintDTO(
        id = id,
        identifier = identifier,
        accountId = account_id
    )
}

fun List<AuthorityHint>.toDTO(): List<AuthorityHintDTO> {
    return map { it.toDTO() }
}

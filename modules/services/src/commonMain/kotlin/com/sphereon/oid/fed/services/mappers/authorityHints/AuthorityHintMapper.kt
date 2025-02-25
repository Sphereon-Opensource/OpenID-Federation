package com.sphereon.oid.fed.services.mappers.authorityHints

import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.persistence.models.AuthorityHint as AuthorityHintEntity

fun AuthorityHintEntity.toDTO(): AuthorityHint {
    return AuthorityHint(
        id = id,
        identifier = identifier,
        accountId = account_id
    )
}

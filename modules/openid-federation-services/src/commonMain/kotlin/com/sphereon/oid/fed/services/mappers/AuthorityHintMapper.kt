package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.openapi.models.AuthorityHintsResponse
import com.sphereon.oid.fed.persistence.models.AuthorityHint as AuthorityHintEntity

fun AuthorityHintEntity.toDTO(): AuthorityHint {
    return AuthorityHint(
        id = id,
        identifier = identifier,
        accountId = account_id
    )
}


fun List<AuthorityHint>.toAuthorityHintsResponse() = AuthorityHintsResponse(this)

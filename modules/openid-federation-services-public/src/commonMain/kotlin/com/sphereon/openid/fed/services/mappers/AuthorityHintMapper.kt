package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.openapi.models.AuthorityHintsResponse
import com.sphereon.openid.fed.persistence.models.AuthorityHint as AuthorityHintEntity

fun AuthorityHintEntity.toDTO(): AuthorityHint {
    return AuthorityHint(
        id = id,
        identifier = identifier,
        accountId = account_id
    )
}


fun List<AuthorityHint>.toAuthorityHintsResponse() = AuthorityHintsResponse(this)

package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint
import com.sphereon.openid.fed.openapi.models.TrustAnchorHintsResponse
import com.sphereon.openid.fed.persistence.models.TrustAnchorHint as TrustAnchorHintEntity

fun TrustAnchorHintEntity.toDTO(): TrustAnchorHint {
    return TrustAnchorHint(
        id = id,
        identifier = identifier,
        accountId = account_id
    )
}

fun List<TrustAnchorHint>.toTrustAnchorHintsResponse() = TrustAnchorHintsResponse(this)

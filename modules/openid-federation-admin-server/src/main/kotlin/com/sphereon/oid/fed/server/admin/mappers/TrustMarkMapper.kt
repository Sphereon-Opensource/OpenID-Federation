package com.sphereon.oid.fed.server.admin.mappers

import com.sphereon.oid.fed.openapi.java.models.CreateTrustMarkRequest
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkRequest as CreateTrustMarkRequestKotlin

fun CreateTrustMarkRequest.toKotlin() = CreateTrustMarkRequestKotlin(
    trustMarkId = trustMarkId,
    sub = sub,
    exp = exp,
    ref = ref.toString(),
    delegation = delegation,
    dryRun = dryRun,
    logoUri = logoUri.toString(),
    iat = iat
)

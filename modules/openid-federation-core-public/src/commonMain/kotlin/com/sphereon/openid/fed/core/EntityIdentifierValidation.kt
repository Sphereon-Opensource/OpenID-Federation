package com.sphereon.openid.fed.core

import com.sphereon.core.api.http.util.validateHttpsUrlSyntax

/** Validate the exact entity identifier's URL syntax without changing its spelling. */
fun isValidEntityIdentifier(value: String): Boolean =
    validateHttpsUrlSyntax(value, allowQuery = false).isOk

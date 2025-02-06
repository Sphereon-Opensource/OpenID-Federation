package com.sphereon.oid.fed.client.types

import kotlin.js.JsExport

/**
 * VerifyTrustChainResponse is a data class that represents the response of a trust chain verification.
 */
@JsExport
data class VerifyTrustChainResponse(
    val isValid: Boolean,
    val error: String? = null
)
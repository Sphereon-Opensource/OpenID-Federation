package com.sphereon.oid.fed.client.types

import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Response object for the resolve operation.
 */
@JsExport
@JsName("TrustChainResolveResponse")
data class TrustChainResolveResponse(
    /**
     * A list of strings representing the resolved trust chain.
     * Each string contains a JWT.
     */
    val trustChain: List<String>? = null,

    /**
     * Indicates whether the resolve operation was successful.
     */
    val error: Boolean = false,

    /**
     * Error message in case of a failure, if any.
     */
    val errorMessage: String? = null
)
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
    val trustChain: Array<String>? = null,

    /**
     * Indicates whether the resolve operation was successful.
     */
    val error: Boolean = false,

    /**
     * Error message in case of a failure, if any.
     */
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TrustChainResolveResponse

        if (error != other.error) return false
        if (trustChain != null) {
            if (other.trustChain == null) return false
            if (!trustChain.contentEquals(other.trustChain)) return false
        } else if (other.trustChain != null) return false
        if (errorMessage != other.errorMessage) return false

        return true
    }

    override fun hashCode(): Int {
        var result = error.hashCode()
        result = 31 * result + (trustChain?.contentHashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}
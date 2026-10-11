package com.sphereon.openid.fed.services.command.jwk

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** A registered or federation-defined historical key revocation reason. */
@Serializable
@JvmInline
value class FederationKeyRevocationReason private constructor(val value: String) {
    init {
        require(TOKEN.matches(value)) { "Invalid key revocation reason token" }
    }

    companion object {
        private val TOKEN = Regex("^[a-z][a-z0-9_]{0,63}$")
        val UNSPECIFIED = FederationKeyRevocationReason("unspecified")
        val COMPROMISED = FederationKeyRevocationReason("compromised")
        val SUPERSEDED = FederationKeyRevocationReason("superseded")

        fun parse(value: String): FederationKeyRevocationReason? =
            if (TOKEN.matches(value)) FederationKeyRevocationReason(value) else null
    }
}

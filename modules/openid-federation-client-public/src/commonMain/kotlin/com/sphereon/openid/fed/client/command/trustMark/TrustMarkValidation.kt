package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.TrustMarkExpiredError
import com.sphereon.openid.fed.core.error.TrustMarkInvalidError
import com.sphereon.openid.fed.openapi.models.Jwt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Shared local checks used by Trust Mark verification and resolution diagnostics. */
object TrustMarkValidation {
    fun numericDate(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeUnless { it.isString }
        ?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun headerError(mark: Jwt): FederationError? {
        if (mark.header.typ != "trust-mark+jwt") return TrustMarkInvalidError("unknown",
            if (mark.header.typ == null) "Trust Mark missing required typ header 'trust-mark+jwt'"
            else "Trust Mark typ must be 'trust-mark+jwt', got '${mark.header.typ}'")
        if (mark.header.alg.isBlank() || mark.header.alg.equals("none", ignoreCase = true)) {
            return TrustMarkInvalidError("unknown", "Trust Mark alg must be a signing algorithm and MUST NOT be 'none'")
        }
        return null
    }

    fun subjectError(mark: Jwt, type: String, subject: String?): FederationError? {
        val actual = mark.payload["sub"]?.jsonPrimitive?.contentOrNull
        return if (subject != null && actual != subject) TrustMarkInvalidError(type,
            "Trust Mark sub '$actual' does not match subject Entity '$subject'") else null
    }

    fun timeError(mark: Jwt, type: String, now: Long): FederationError? {
        val iat = numericDate(mark.payload["iat"])
        if (iat == null || iat > now.toDouble() + 5.0) return TrustMarkInvalidError(type, "Trust Mark has invalid or future iat")
        mark.payload["exp"]?.let {
            val exp = numericDate(it)
            if (exp == null || exp <= now.toDouble() - 5.0) return TrustMarkExpiredError(type)
        }
        return null
    }
}

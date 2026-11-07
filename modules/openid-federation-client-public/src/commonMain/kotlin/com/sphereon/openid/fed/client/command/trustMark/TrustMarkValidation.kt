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

    /**
     * Checks the Trust Mark `iat` and `exp` against [now]. Without a [policy] the tolerance is
     * [DEFAULT_CLOCK_SKEW_SECONDS]; with one it is the policy's allowed skew, and both claims must also be
     * representable epoch seconds (0 through [MAX_SAFE_EPOCH_SECONDS]).
     */
    fun timeFault(mark: Jwt, now: Long, policy: TrustMarkStatusPolicy?): TrustMarkTimeFault? {
        val skew = policy?.allowedClockSkewSeconds ?: DEFAULT_CLOCK_SKEW_SECONDS
        fun representable(value: Double) = policy == null || value in 0.0..MAX_SAFE_EPOCH_SECONDS.toDouble()
        val iat = numericDate(mark.payload["iat"])
        if (iat == null || !representable(iat)) return TrustMarkTimeFault.IAT_INVALID
        if (iat > now.toDouble() + skew) return TrustMarkTimeFault.IAT_IN_FUTURE
        mark.payload["exp"]?.let {
            val exp = numericDate(it)
            if (exp == null || !representable(exp)) return TrustMarkTimeFault.EXP_INVALID
            if (exp <= now.toDouble() - skew) return TrustMarkTimeFault.EXPIRED
        }
        return null
    }

    fun timeError(fault: TrustMarkTimeFault, type: String): FederationError = when (fault) {
        TrustMarkTimeFault.IAT_INVALID, TrustMarkTimeFault.IAT_IN_FUTURE ->
            TrustMarkInvalidError(type, "Trust Mark has invalid or future iat")
        TrustMarkTimeFault.EXP_INVALID, TrustMarkTimeFault.EXPIRED -> TrustMarkExpiredError(type)
    }

    fun timeError(mark: Jwt, type: String, now: Long): FederationError? =
        timeFault(mark, now, null)?.let { timeError(it, type) }
}

/** Why a Trust Mark's `iat` or `exp` was refused. */
enum class TrustMarkTimeFault { IAT_INVALID, IAT_IN_FUTURE, EXP_INVALID, EXPIRED }

private const val DEFAULT_CLOCK_SKEW_SECONDS = 5L
const val MAX_SAFE_EPOCH_SECONDS = 9_007_199_254_740_991L

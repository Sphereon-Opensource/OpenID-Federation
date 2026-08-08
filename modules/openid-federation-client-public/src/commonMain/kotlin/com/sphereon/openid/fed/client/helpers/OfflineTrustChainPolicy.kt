package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deployment policy for **offline** Trust Chain acceptance beyond Entity Statement `exp`/`iat`
 * checks already performed by Trust Chain verification (OIDFed 1.1 §3.2 / §10.2).
 *
 * Use when a pre-built chain (e.g. JWT `trust_chain` header) must be treated as short-lived
 * even if individual statements still have long `exp` values.
 *
 * @param maxAgeSeconds Maximum age of the chain snapshot: `now - max(iat)` must not exceed this.
 *   `null` = no max-age constraint.
 * @param minRemainingSeconds Minimum time until the chain expires: `min(exp) - now` must be at least this.
 *   `null` = no min-remaining constraint (only statement-level `exp` applies).
 * @param clockSkewSeconds Clock skew allowance applied to both bounds (default 5s).
 */
data class OfflineTrustChainPolicy(
    val maxAgeSeconds: Long? = null,
    val minRemainingSeconds: Long? = null,
    val clockSkewSeconds: Long = DEFAULT_CLOCK_SKEW_SECONDS,
) {
    val isEnabled: Boolean
        get() = maxAgeSeconds != null || minRemainingSeconds != null

    companion object {
        const val DEFAULT_CLOCK_SKEW_SECONDS = 5L

        /** No extra offline constraints (only structural/crypto/`exp` from chain verify). */
        val DISABLED = OfflineTrustChainPolicy()

        /**
         * Reasonable high-assurance wallet default: chain snapshot at most 1 hour old,
         * and at least 60 seconds remaining before earliest statement `exp`.
         */
        val SHORT_LIVED_DEFAULT = OfflineTrustChainPolicy(
            maxAgeSeconds = 3600L,
            minRemainingSeconds = 60L,
        )
    }

    data class Evaluation(
        val ok: Boolean,
        val reason: String? = null,
        val maxIat: Long? = null,
        val minExp: Long? = null,
        val ageSeconds: Long? = null,
        val remainingSeconds: Long? = null,
    )

    /**
     * Evaluate policy against decoded statement payloads (or compact JWTs via [evaluateChain]).
     */
    fun evaluate(
        statementPayloads: List<JsonObject>,
        currentTimeSeconds: Long,
    ): Evaluation {
        if (!isEnabled) {
            return Evaluation(ok = true, reason = "Offline freshness policy disabled")
        }
        if (statementPayloads.isEmpty()) {
            return Evaluation(ok = false, reason = "trust_chain is empty")
        }

        var maxIat: Long? = null
        var minExp: Long? = null
        for (payload in statementPayloads) {
            val iat = claimEpoch(payload, "iat")
            val exp = claimEpoch(payload, "exp")
            if (iat != null) {
                maxIat = if (maxIat == null) iat else maxOf(maxIat, iat)
            }
            if (exp != null) {
                minExp = if (minExp == null) exp else minOf(minExp, exp)
            }
        }

        val skew = clockSkewSeconds.coerceAtLeast(0L)

        if (maxAgeSeconds != null) {
            if (maxIat == null) {
                return Evaluation(
                    ok = false,
                    reason = "Offline policy maxAgeSeconds=$maxAgeSeconds requires iat on statements",
                )
            }
            val age = currentTimeSeconds - maxIat
            if (age > maxAgeSeconds + skew) {
                return Evaluation(
                    ok = false,
                    reason = "Offline trust chain too old: age=${age}s exceeds maxAgeSeconds=$maxAgeSeconds " +
                        "(maxIat=$maxIat, now=$currentTimeSeconds)",
                    maxIat = maxIat,
                    minExp = minExp,
                    ageSeconds = age,
                    remainingSeconds = minExp?.let { it - currentTimeSeconds },
                )
            }
        }

        if (minRemainingSeconds != null) {
            if (minExp == null) {
                return Evaluation(
                    ok = false,
                    reason = "Offline policy minRemainingSeconds=$minRemainingSeconds requires exp on statements",
                    maxIat = maxIat,
                )
            }
            val remaining = minExp - currentTimeSeconds
            if (remaining < minRemainingSeconds - skew) {
                return Evaluation(
                    ok = false,
                    reason = "Offline trust chain expires too soon: remaining=${remaining}s " +
                        "below minRemainingSeconds=$minRemainingSeconds (minExp=$minExp, now=$currentTimeSeconds)",
                    maxIat = maxIat,
                    minExp = minExp,
                    ageSeconds = maxIat?.let { currentTimeSeconds - it },
                    remainingSeconds = remaining,
                )
            }
        }

        return Evaluation(
            ok = true,
            maxIat = maxIat,
            minExp = minExp,
            ageSeconds = maxIat?.let { currentTimeSeconds - it },
            remainingSeconds = minExp?.let { it - currentTimeSeconds },
        )
    }

    /**
     * Evaluate policy against compact Entity Statement JWTs in chain order (leaf first).
     */
    fun evaluateChain(
        trustChainJwts: List<String>,
        currentTimeSeconds: Long,
    ): Evaluation {
        if (trustChainJwts.isEmpty()) {
            return Evaluation(ok = false, reason = "trust_chain is empty")
        }
        val payloads = try {
            trustChainJwts.map { decodeJWTComponents(it).payload }
        } catch (e: Exception) {
            return Evaluation(ok = false, reason = "Failed to decode trust_chain for policy: ${e.message}")
        }
        return evaluate(payloads, currentTimeSeconds)
    }

    private fun claimEpoch(payload: JsonObject, name: String): Long? {
        val raw = payload[name]?.jsonPrimitive?.contentOrNull ?: return null
        return raw.toDoubleOrNull()?.toLong()
    }
}

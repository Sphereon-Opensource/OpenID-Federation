package com.sphereon.openid.fed.wallet.policy

import com.sphereon.openid.fed.client.helpers.JwtTrustChainHeader
import com.sphereon.openid.fed.openapi.models.Jwt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wallet Attestation claim / header profile checks.
 *
 * Aligns with OAuth 2.0 Attestation-Based Client Authentication
 * (`typ` = `oauth-client-attestation+jwt`) and wallet architecture usage where the
 * attestation is issued by a Wallet Provider outside the Trust Chain, optionally
 * carrying an OpenID Federation `trust_chain` header for offline verification.
 */
object WalletAttestationProfile {

    /** Primary typ from OAuth Attestation-Based Client Authentication. */
    const val TYP_OAUTH_CLIENT_ATTESTATION = "oauth-client-attestation+jwt"

    /** Alternate typ seen in some wallet deployments. */
    const val TYP_WALLET_ATTESTATION = "wallet-attestation+jwt"

    val ACCEPTED_TYP_VALUES: Set<String> = setOf(
        TYP_OAUTH_CLIENT_ATTESTATION,
        TYP_WALLET_ATTESTATION,
    )

    private const val CLOCK_SKEW_SECONDS = 5L

    data class ProfileResult(
        val ok: Boolean,
        val reason: String? = null,
        val checks: List<MetadataValidationCheck> = emptyList(),
    )

    /**
     * Validate header typ and required claims (iss, sub, exp, iat).
     *
     * Does not perform cryptographic verification or federation trust evaluation.
     */
    fun validateStructure(
        jwt: Jwt,
        currentTimeSeconds: Long,
    ): ProfileResult {
        val checks = mutableListOf<MetadataValidationCheck>()
        val header = jwt.header
        val payload = jwt.payload

        val typ = header.typ
        val typOk = typ != null && typ in ACCEPTED_TYP_VALUES
        checks.add(
            MetadataValidationCheck(
                check = "attestation.typ",
                passed = typOk,
                detail = if (!typOk)
                    "typ must be one of $ACCEPTED_TYP_VALUES, got '${typ ?: "(missing)"}'"
                else null,
                profile = MetadataValidationCheck.PROFILE_WALLET,
            ),
        )

        val kid = header.kid
        val kidOk = !kid.isNullOrBlank()
        checks.add(
            MetadataValidationCheck(
                check = "attestation.kid",
                passed = kidOk,
                detail = if (!kidOk) "Missing required 'kid' header" else null,
                profile = MetadataValidationCheck.PROFILE_WALLET,
            ),
        )

        val iss = payload.claimString("iss")
        val issOk = !iss.isNullOrBlank()
        checks.add(
            MetadataValidationCheck(
                check = "attestation.iss",
                passed = issOk,
                detail = if (!issOk) "Missing required 'iss' claim (Wallet Provider Entity Identifier)" else null,
                profile = MetadataValidationCheck.PROFILE_WALLET,
            ),
        )

        val sub = payload.claimString("sub")
        val subOk = !sub.isNullOrBlank()
        checks.add(
            MetadataValidationCheck(
                check = "attestation.sub",
                passed = subOk,
                detail = if (!subOk) "Missing required 'sub' claim (Wallet Instance / client identifier)" else null,
                profile = MetadataValidationCheck.PROFILE_WALLET,
            ),
        )

        val exp = payload.claimLong("exp")
        val expOk = exp != null && exp > currentTimeSeconds - CLOCK_SKEW_SECONDS
        checks.add(
            MetadataValidationCheck(
                check = "attestation.exp",
                passed = expOk,
                detail = when {
                    exp == null -> "Missing required 'exp' claim"
                    !expOk -> "Wallet attestation expired (exp=$exp, now=$currentTimeSeconds)"
                    else -> null
                },
                profile = MetadataValidationCheck.PROFILE_WALLET,
            ),
        )

        val iat = payload.claimLong("iat")
        // iat is RECOMMENDED; when present must not be in the future
        if (iat != null) {
            val iatOk = iat <= currentTimeSeconds + CLOCK_SKEW_SECONDS
            checks.add(
                MetadataValidationCheck(
                    check = "attestation.iat",
                    passed = iatOk,
                    detail = if (!iatOk)
                        "iat is in the future (iat=$iat, now=$currentTimeSeconds)"
                    else null,
                    profile = MetadataValidationCheck.PROFILE_WALLET,
                ),
            )
        }

        val failed = checks.filter { !it.passed }
        return if (failed.isEmpty()) {
            ProfileResult(ok = true, checks = checks)
        } else {
            ProfileResult(
                ok = false,
                reason = failed.joinToString("; ") { it.detail ?: it.check },
                checks = checks,
            )
        }
    }

    /**
     * Extract the OpenID Federation `trust_chain` header value as a list of JWT strings.
     * Delegates to [JwtTrustChainHeader] (shared client helper for item 22).
     */
    fun extractTrustChain(jwt: Jwt): List<String>? = JwtTrustChainHeader.extract(jwt)

    private fun JsonObject.claimString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.claimLong(name: String): Long? {
        val el = this[name] ?: return null
        return when (el) {
            is JsonPrimitive -> el.longOrNull
                ?: el.contentOrNull?.toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}

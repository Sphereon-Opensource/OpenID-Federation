package com.sphereon.openid.fed.wallet.policy

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletAttestationProfileTest {

    private val now = 1_700_000_000L

    @Test
    fun validAttestationProfilePasses() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            iss = "https://wp.example",
            sub = "wallet-instance-1",
            exp = now + 3600,
            iat = now - 10,
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertTrue(result.ok, result.reason)
        assertTrue(result.checks.all { it.passed })
    }

    @Test
    fun walletAttestationTypAlsoAccepted() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_WALLET_ATTESTATION,
            iss = "https://wp.example",
            sub = "wallet-1",
            exp = now + 60,
            iat = now,
        )
        assertTrue(WalletAttestationProfile.validateStructure(jwt, now).ok)
    }

    @Test
    fun wrongTypFails() {
        val jwt = attestation(
            typ = "JWT",
            iss = "https://wp.example",
            sub = "w",
            exp = now + 60,
            iat = now,
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("typ"))
    }

    @Test
    fun missingSubFails() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            iss = "https://wp.example",
            sub = null,
            exp = now + 60,
            iat = now,
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertFalse(result.ok)
        assertTrue(result.checks.any { it.check == "attestation.sub" && !it.passed })
    }

    @Test
    fun expiredFails() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            iss = "https://wp.example",
            sub = "w",
            exp = now - 100,
            iat = now - 200,
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertFalse(result.ok)
        assertTrue(result.checks.any { it.check == "attestation.exp" && !it.passed })
    }

    @Test
    fun futureIatFails() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            iss = "https://wp.example",
            sub = "w",
            exp = now + 3600,
            iat = now + 1000,
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertFalse(result.ok)
        assertTrue(result.checks.any { it.check == "attestation.iat" && !it.passed })
    }

    @Test
    fun extractTrustChainFromHeader() {
        val chain = listOf("eyJ.leaf.sig", "eyJ.ta.sig")
        val jwt = Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "k1",
                typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
                trustChain = chain,
            ),
            payload = JsonObject(
                mapOf(
                    "iss" to JsonPrimitive("https://wp.example"),
                    "sub" to JsonPrimitive("w"),
                    "exp" to JsonPrimitive(now + 60),
                )
            ),
            signature = "sig",
        )
        assertEquals(chain, WalletAttestationProfile.extractTrustChain(jwt))
    }

    @Test
    fun extractTrustChainAbsent() {
        val jwt = attestation(
            typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            iss = "https://wp.example",
            sub = "w",
            exp = now + 60,
            iat = now,
        )
        assertNull(WalletAttestationProfile.extractTrustChain(jwt))
    }

    @Test
    fun missingKidFails() {
        val jwt = Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "",
                typ = WalletAttestationProfile.TYP_OAUTH_CLIENT_ATTESTATION,
            ),
            payload = JsonObject(
                mapOf(
                    "iss" to JsonPrimitive("https://wp.example"),
                    "sub" to JsonPrimitive("w"),
                    "exp" to JsonPrimitive(now + 60),
                    "iat" to JsonPrimitive(now),
                )
            ),
            signature = "sig",
        )
        val result = WalletAttestationProfile.validateStructure(jwt, now)
        assertFalse(result.ok)
        assertNotNull(result.checks.firstOrNull { it.check == "attestation.kid" && !it.passed })
    }

    private fun attestation(
        typ: String?,
        iss: String?,
        sub: String?,
        exp: Long?,
        iat: Long?,
    ): Jwt {
        val claims = buildMap {
            if (iss != null) put("iss", JsonPrimitive(iss))
            if (sub != null) put("sub", JsonPrimitive(sub))
            if (exp != null) put("exp", JsonPrimitive(exp))
            if (iat != null) put("iat", JsonPrimitive(iat))
        }
        return Jwt(
            header = JwtHeader(
                alg = "ES256",
                kid = "wp-key-1",
                typ = typ,
            ),
            payload = JsonObject(claims),
            signature = "sig",
        )
    }
}

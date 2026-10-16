package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** These local signatures test authority-key binding only; they are not peer interoperability evidence. */
class TrustedJwtVerifierTest {
    private val authority = ecPair()
    private val attacker = ecPair()
    private val leaf = ecPair()
    private val now = Instant.parse("2026-10-15T12:00:00Z")

    @Test
    fun `authority signature verifies subordinate statement with the leaf public JWKS in its payload`() {
        val token = subordinateStatement(authority, "authority-key")
        val claims = TrustedJwtVerifier.verify(
            token,
            "entity-statement+jwt",
            "https://authority.unit.test",
            "https://leaf.unit.test",
            jwks(authority, "authority-key"),
            now,
        )
        assertEquals("https://authority.unit.test", claims["iss"])
        assertEquals("https://leaf.unit.test", claims["sub"])
    }

    @Test
    fun `attacker cannot substitute its embedded public JWKS for the trusted authority key`() {
        val token = subordinateStatement(attacker, "authority-key", jwks(attacker, "attacker-key"))
        assertFailsWith<IllegalArgumentException> {
            TrustedJwtVerifier.verify(
                token,
                "entity-statement+jwt",
                "https://authority.unit.test",
                "https://leaf.unit.test",
                jwks(authority, "authority-key"),
                now,
            )
        }
    }

    private fun subordinateStatement(
        signer: KeyPair,
        signerKid: String,
        publishedJwks: Map<String, Any?> = jwks(leaf, "leaf-key"),
    ): String {
        val payload = JWTClaimsSet.Builder()
            .issuer("https://authority.unit.test")
            .subject("https://leaf.unit.test")
            .issueTime(Date.from(now.minusSeconds(10)))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .claim("jwks", publishedJwks)
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("entity-statement+jwt")).keyID(signerKid).build(),
            payload,
        )
        jwt.sign(ECDSASigner(signer.private as java.security.interfaces.ECPrivateKey))
        return jwt.serialize()
    }

    private fun jwks(pair: KeyPair, kid: String): Map<String, Any?> =
        mapOf("keys" to listOf(ECKey.Builder(Curve.P_256, pair.public as java.security.interfaces.ECPublicKey).keyID(kid).build().toJSONObject()))

    private fun ecPair(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
}

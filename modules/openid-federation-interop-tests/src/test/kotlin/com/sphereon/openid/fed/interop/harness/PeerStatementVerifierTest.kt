package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** These generated fixtures exercise verifier invariants only; they are not peer interoperability evidence. */
class PeerStatementVerifierTest {
    private val entity = "https://unit.test"
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val secondRsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val kid = "unit-key"

    @Test
    fun `locally generated statements verify for all planned RSA and EC object algorithms`() {
        listOf(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512).forEach { algorithm ->
            val verified = verify(fixture(algorithm = algorithm))
            assertEquals(entity, verified.issuer)
            assertEquals(algorithm.name, verified.algorithm)
            assertEquals(kid, verified.kid)
        }
    }

    @Test fun `wrong issuer is rejected`() = rejected(fixture(issuer = "https://other.test"))
    @Test fun `wrong subject is rejected`() = rejected(fixture(subject = "https://other.test"))
    @Test fun `expired statement is rejected`() = rejected(fixture(expiresAt = now.minusSeconds(1)))
    @Test fun `future issued statement is rejected`() = rejected(fixture(issuedAt = now.plusSeconds(1)))
    @Test fun `wrong object type is rejected`() = rejected(fixture(typ = "JWT"))
    @Test fun `private JWK parameters are rejected`() = rejected(fixture(includePrivateJwk = true))
    @Test fun `current key id must be present in header`() = rejected(fixture(includeKid = false))
    @Test fun `current key id must identify exactly one advertised key`() = rejected(fixture(duplicateKid = true))

    @Test
    fun `unsupported MAC algorithm is rejected even when its signature is valid`() {
        val compact = fixture(algorithm = JWSAlgorithm.HS256, signerKind = SignerKind.HMAC)
        rejected(compact)
    }

    @Test
    fun `mutated signature is rejected`() {
        val token = fixture()
        val parts = token.split('.')
        val changed = if (parts[2][0] == 'A') 'B' else 'A'
        rejected("${parts[0]}.${parts[1]}.$changed${parts[2].drop(1)}")
    }

    @Test
    fun `JWKS object and key ordering do not change the canonical public key set`() {
        val ordinary = verify(fixture()).jwks
        val reordered = verify(fixture(reverseJwks = true)).jwks
        assertEquals(ordinary, reordered)
    }

    private fun rejected(token: String) {
        assertFailsWith<IllegalArgumentException> { verify(token) }
    }

    private fun verify(token: String) = PeerStatementVerifier.verify(token, entity, now)

    private fun fixture(
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
        issuer: String = entity,
        subject: String = entity,
        issuedAt: Instant = now.minusSeconds(30),
        expiresAt: Instant = now.plusSeconds(300),
        typ: String = "entity-statement+jwt",
        includeKid: Boolean = true,
        includePrivateJwk: Boolean = false,
        duplicateKid: Boolean = false,
        reverseJwks: Boolean = false,
        signerKind: SignerKind = SignerKind.ALGORITHM,
    ): String {
        val ecPair = if (algorithm in setOf(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512)) ecKeyPair(algorithm) else null
        val primary = signingJwk(algorithm, ecPair)
        val other = rsaPublicJwk(secondRsa, if (duplicateKid) kid else "other-key")
        val keys = listOf(primary.toJSONObject(), other.toJSONObject()).map { key ->
            val output = if (includePrivateJwk && key["kid"] == kid) {
                val full = RSAKey.Builder(rsa.public as java.security.interfaces.RSAPublicKey)
                    .privateKey(rsa.private as java.security.interfaces.RSAPrivateKey).keyID(kid).build().toJSONObject()
                full
            } else key
            val entries = if (reverseJwks) output.entries.reversed() else output.entries
            entries.associateTo(linkedMapOf()) { it.key to it.value }
        }.let { if (reverseJwks) it.reversed() else it }
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .claim("jwks", if (reverseJwks) linkedMapOf("keys" to keys, "cache" to "same") else linkedMapOf("cache" to "same", "keys" to keys))
            .build()
        val builder = JWSHeader.Builder(algorithm).type(JOSEObjectType(typ))
        if (includeKid) builder.keyID(kid)
        val token = SignedJWT(builder.build(), claims)
        when (signerKind) {
            SignerKind.HMAC -> token.sign(MACSigner(ByteArray(32) { 7 }))
            SignerKind.ALGORITHM -> when (algorithm) {
                JWSAlgorithm.RS256 -> token.sign(RSASSASigner(rsa.private))
                JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 -> token.sign(ECDSASigner(ecPair!!.private as java.security.interfaces.ECPrivateKey))
                else -> token.sign(RSASSASigner(rsa.private))
            }
        }
        return token.serialize()
    }

    private fun signingJwk(algorithm: JWSAlgorithm, ecPair: KeyPair?): JWK = when (algorithm) {
        JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 -> ecKey(ecPair!!, algorithm)
        else -> rsaPublicJwk(rsa, kid)
    }

    private fun ecKeyPair(algorithm: JWSAlgorithm): KeyPair {
        val curve = when (algorithm) {
            JWSAlgorithm.ES256 -> "secp256r1"
            JWSAlgorithm.ES384 -> "secp384r1"
            JWSAlgorithm.ES512 -> "secp521r1"
            else -> error("not an ECDSA algorithm")
        }
        return KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec(curve)) }.generateKeyPair()
    }

    private fun ecKey(pair: KeyPair, algorithm: JWSAlgorithm): ECKey {
        val curve = when (algorithm) {
            JWSAlgorithm.ES256 -> Curve.P_256
            JWSAlgorithm.ES384 -> Curve.P_384
            JWSAlgorithm.ES512 -> Curve.P_521
            else -> error("not an ECDSA algorithm")
        }
        // Only the public half is published in JWKS; ECDSASigner receives the private key separately.
        return ECKey.Builder(curve, pair.public as java.security.interfaces.ECPublicKey).keyID(kid).build()
    }

    private fun rsaPublicJwk(pair: KeyPair, id: String) =
        RSAKey.Builder(pair.public as java.security.interfaces.RSAPublicKey).keyID(id).build()

    private enum class SignerKind { ALGORITHM, HMAC }
}

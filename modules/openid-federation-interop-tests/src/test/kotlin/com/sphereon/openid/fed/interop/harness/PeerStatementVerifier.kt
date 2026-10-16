package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jose.util.JSONObjectUtils
import java.time.Instant

internal data class VerifiedStatement(val issuer: String, val kid: String, val jwks: String, val algorithm: String)

/** Verifies an Entity Configuration using only its advertised public signing key. */
internal object PeerStatementVerifier {
    fun verify(compact: String, expectedEntityId: String, now: Instant = Instant.now()): VerifiedStatement {
        val jwt = SignedJWT.parse(compact)
        require(jwt.header.type?.type == "entity-statement+jwt") { "unexpected entity statement typ" }
        val payload = jwt.payload.toJSONObject()
        val issuer = JSONObjectUtils.getString(payload, "iss")
        val subject = JSONObjectUtils.getString(payload, "sub")
        require(issuer == expectedEntityId && subject == expectedEntityId) { "entity statement identity mismatch" }
        val issuedAt = jwt.jwtClaimsSet.issueTime?.toInstant() ?: error("missing iat")
        val expiresAt = jwt.jwtClaimsSet.expirationTime?.toInstant() ?: error("missing exp")
        require(!issuedAt.isAfter(now) && expiresAt.isAfter(now)) { "entity statement is outside its validity window" }
        val jwksObject = JSONObjectUtils.getJSONObject(payload, "jwks")
        val keys = jwksObject["keys"] as? List<*> ?: error("missing public JWKS keys")
        require(keys.isNotEmpty()) { "empty public JWKS" }
        val publicKeys = keys.map { item ->
            val key = item as? Map<*, *> ?: error("invalid JWK entry")
            require(key.keys.none { it?.toString() in PRIVATE_JWK_FIELDS }) { "peer published private JWK parameters" }
            JWK.parse(key.entries.associate { it.key.toString() to it.value }).toPublicJWK()
        }

        val kid = requireNotNull(jwt.header.keyID) { "missing current signing key id" }
        val jwk = requireNotNull(publicKeys.singleOrNull { it.keyID == kid }) {
            "current signing key id is absent or ambiguous in advertised JWKS"
        }
        val valid = when (jwt.header.algorithm) {
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512 ->
                (jwk as? RSAKey)?.toRSAPublicKey()?.let { jwt.verify(RSASSAVerifier(it)) } ?: false
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 ->
                (jwk as? ECKey)?.toECPublicKey()?.let { jwt.verify(ECDSAVerifier(it)) } ?: false
            else -> false
        }
        require(valid) { "entity statement signature verification failed" }
        return VerifiedStatement(issuer, kid, canonicalPublicJwks(publicKeys), jwt.header.algorithm.name)
    }

    private fun canonicalPublicJwks(keys: List<JWK>): String = keys
        .map { key ->
            val sorted = java.util.TreeMap<String, Any>()
            key.toJSONObject().forEach { (name, value) -> sorted[name] = value }
            JSONObjectUtils.toJSONString(sorted)
        }
        .sorted()
        .joinToString(prefix = "[", postfix = "]", separator = ",")

    private val PRIVATE_JWK_FIELDS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")
}

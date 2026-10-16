package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.time.Instant

/** Verifies a signed federation object using the issuer's independently fetched public JWKS. */
internal object TrustedJwtVerifier {
    fun verify(
        compact: String,
        expectedType: String,
        expectedIssuer: String,
        expectedSubject: String,
        issuerJwks: Map<String, Any?>,
        now: Instant = Instant.now(),
    ): Map<String, Any> {
        val jwt = SignedJWT.parse(compact)
        require(jwt.header.type?.type == expectedType) { "unexpected signed-object typ" }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == expectedIssuer && claims.subject == expectedSubject) { "signed-object issuer or subject mismatch" }
        require(claims.issueTime?.toInstant()?.let { !it.isAfter(now) } == true) { "signed object has no current iat" }
        require(claims.expirationTime?.toInstant()?.isAfter(now) == true) { "signed object has no current exp" }
        val kid = requireNotNull(jwt.header.keyID) { "signed object has no kid" }
        val rawKeys = issuerJwks["keys"] as? List<*> ?: error("issuer JWKS has no keys")
        val issuerKeys = rawKeys.map { raw ->
            val objectValue = raw as? Map<*, *> ?: error("invalid issuer JWK")
            require(objectValue.keys.none { it?.toString() in PRIVATE_JWK_FIELDS }) { "issuer JWKS contains private key material" }
            JWK.parse(objectValue.entries.associate { it.key.toString() to it.value }).toPublicJWK()
        }
        val key = issuerKeys.singleOrNull { it.keyID == kid } ?: error("issuer JWKS does not contain exactly one signing kid")
        val valid = when (jwt.header.algorithm) {
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512 ->
                (key as? RSAKey)?.toRSAPublicKey()?.let { jwt.verify(RSASSAVerifier(it)) } ?: false
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 ->
                (key as? ECKey)?.toECPublicKey()?.let { jwt.verify(ECDSAVerifier(it)) } ?: false
            else -> false
        }
        require(valid) { "signed-object signature is invalid under issuer JWKS" }
        return jwt.payload.toJSONObject()
    }

    private val PRIVATE_JWK_FIELDS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")
}

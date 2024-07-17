package com.sphereon.oid.fed.common.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.*

actual fun sign(
    payload: String,
    opts: MutableMap<String, Any>?
): String {
    var rsaJWK = opts?.get("key") as RSAKey?
    val kid = rsaJWK?.keyID ?: UUID.randomUUID().toString()
    val header: JWSHeader?
    if (opts?.get("jwtHeader") != null) {
        header = JWSHeader.parse(opts["jwtHeader"] as String?)
    } else {
        header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build()
    }

    if (rsaJWK == null) {
        rsaJWK = RSAKeyGenerator(2048)
            .keyID(kid)
            .generate()
    }

    val signer: JWSSigner = RSASSASigner(rsaJWK)

    val claimsSet = JWTClaimsSet.parse(payload)

    val signedJWT = SignedJWT(
        header,
        claimsSet
    )

    signedJWT.sign(signer)
    return signedJWT.serialize()
}

actual fun verify(
    jwt: String,
    key: Any,
    opts: MutableMap<String, Any>?
): Boolean {
    try {
        val rsaKey = key as RSAKey
        val verifier: JWSVerifier = RSASSAVerifier(rsaKey)
        val signedJWT = SignedJWT.parse(jwt)
        val verified = signedJWT.verify(verifier)
        return verified
    } catch (e: Exception) {
        throw Exception("Couldn't verify the JWT Signature: ${e.message}")
    }
}
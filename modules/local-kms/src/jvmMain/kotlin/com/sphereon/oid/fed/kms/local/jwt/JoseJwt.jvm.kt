package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT

actual typealias JwtPayload = JWTClaimsSet
actual typealias JwtHeader = JWSHeader

actual fun sign(
    payload: JwtPayload,
    header: JwtHeader,
    opts: Map<String, Any>
): String {
    val rsaJWK = opts["key"] as RSAKey? ?: throw IllegalArgumentException("The RSA key pair is required")

    val signer: JWSSigner = RSASSASigner(rsaJWK)

    val signedJWT = SignedJWT(
        header,
        payload
    )

    signedJWT.sign(signer)
    return signedJWT.serialize()
}

actual fun verify(
    jwt: String,
    key: Any,
    opts: Map<String, Any>
): Boolean {
    try {
        val rsaKey = key as RSAKey
        val verifier: JWSVerifier = RSASSAVerifier(rsaKey)
        val signedJWT = SignedJWT.parse(jwt)
        val verified = signedJWT.verify(verifier)
        return verified
    } catch (e: Exception) {
        throw Exception("Couldn't verify the JWT Signature: ${e.message}", e)
    }
}
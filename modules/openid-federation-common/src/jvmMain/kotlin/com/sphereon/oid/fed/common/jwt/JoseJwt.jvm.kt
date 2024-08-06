package com.sphereon.oid.fed.common.jwt

import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.JwtWithPrivateKey

import java.util.*

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

actual fun generateKeyPair(): JwtWithPrivateKey {
    try {
        val ecKey: ECKey = ECKeyGenerator(Curve.P_256)
            .keyIDFromThumbprint(true)
            .algorithm(Algorithm("EC"))
            .issueTime(Date())
            .expirationTime(Calendar.getInstance().apply {
                time = Date()
                add(Calendar.YEAR, 1)
            }.time)
            .generate()

        return JwtWithPrivateKey(
            d = ecKey.d.toString(),
            alg = ecKey.algorithm.name,
            crv = ecKey.curve.name,
            kid = ecKey.keyID,
            kty = ecKey.keyType.value,
            use = ecKey.keyUse?.value ?: "sig",
            x = ecKey.x.toString(),
            y = ecKey.y.toString()
        )

    } catch (e: Exception) {
        throw Exception("Couldn't generate the EC Key Pair: ${e.message}", e)
    }
}

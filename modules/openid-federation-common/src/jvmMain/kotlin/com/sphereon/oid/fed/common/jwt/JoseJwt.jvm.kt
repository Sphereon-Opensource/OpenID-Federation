package com.sphereon.oid.fed.common.jwt

import com.nimbusds.jose.*
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.json.JsonObject


actual fun sign(
    payload: JsonObject,
    header: JWTHeader,
    opts: Map<String, Any>
): String {
    val rsaJWK = opts["key"] as RSAKey? ?: throw IllegalArgumentException("The RSA key pair is required")

    val signer: JWSSigner = RSASSASigner(rsaJWK)

    val signedJWT = SignedJWT(
        header.toJWSHeader(),
        JWTClaimsSet.parse(payload.toString())
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

fun JWTHeader.toJWSHeader(): JWSHeader {
    val type = typ
    return JWSHeader.Builder(JWSAlgorithm.parse(alg)).apply {
        type(JOSEObjectType(type))
        keyID(kid)
    }.build()
}

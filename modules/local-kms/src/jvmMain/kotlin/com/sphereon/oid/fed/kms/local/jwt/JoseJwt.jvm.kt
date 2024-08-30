package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject


actual fun sign(
    payload: JsonObject,
    header: JWTHeader,
    key: Jwk
): String {
    val rsaJWK = key.toRsaKey()

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
    key: Jwk
): Boolean {
    try {
        val rsaKey = key.toRsaKey()
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

//TODO: Double check the logic
fun Jwk.toRsaKey(): RSAKey {
    return RSAKey.parse(this.toString())
}

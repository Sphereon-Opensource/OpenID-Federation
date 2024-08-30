package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
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
    val ecJWK = ECKey.parse(key.toString())

    val signer: JWSSigner = ECDSASigner(ecJWK)

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
        val ecKey = ECKey.parse(key.toString()) // Parse JWK into ECKey
        val verifier: JWSVerifier = ECDSAVerifier(ecKey)
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

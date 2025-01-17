package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

actual fun sign(
    payload: JsonObject, header: JWTHeader, key: JwkWithPrivateKey
): String {
    val jwkJsonString = Json.encodeToString(key)
    val ecJWK = ECKey.parse(jwkJsonString)
    val signer: JWSSigner = ECDSASigner(ecJWK)
    val jwsHeader = header.toJWSHeader()

    val signedJWT = SignedJWT(
        jwsHeader, JWTClaimsSet.parse(payload.toString())
    )

    signedJWT.sign(signer)
    return signedJWT.serialize()
}

actual fun verify(
    jwt: String, key: Jwk
): Boolean {
    try {
        val jwkJsonString = Json.encodeToString(key)
        val ecKey = ECKey.parse(jwkJsonString)
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

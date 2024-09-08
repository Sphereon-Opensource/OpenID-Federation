package com.sphereon.oid.fed.kms.local.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

actual fun sign(
    payload: JsonObject, header: JWTHeader, key: Jwk
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

fun JWTHeader.toJWSHeader(): JWSHeader {
    val type = typ
    return JWSHeader.Builder(JWSAlgorithm.parse(alg)).apply {
        type(JOSEObjectType(type))
        keyID(kid)
    }.build()
}

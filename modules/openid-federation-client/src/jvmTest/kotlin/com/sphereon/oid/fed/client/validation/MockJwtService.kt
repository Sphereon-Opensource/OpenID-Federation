package com.sphereon.oid.fed.client.validation

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
import com.sphereon.oid.fed.common.jwt.IJwtService
import com.sphereon.oid.fed.common.jwt.JwtSignInput
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
import com.sphereon.oid.fed.openapi.models.JWTHeader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class MockJwtService : IJwtService {

    override suspend fun sign(input: JwtSignInput): String {
        val jwkJsonString = Json.encodeToString(input.key)
        val ecJWK = ECKey.parse(jwkJsonString)
        val signer: JWSSigner = ECDSASigner(ecJWK)
        val jwsHeader = input.header.toJWSHeader()

        val signedJWT = SignedJWT(
            jwsHeader, JWTClaimsSet.parse(JsonObject(input.payload).toString())
        )

        signedJWT.sign(signer)
        return signedJWT.serialize()
    }

    override suspend fun verify(input: JwtVerifyInput): Boolean {
        try {
            val jwkJsonString = Json.encodeToString(input.key)
            val ecKey = ECKey.parse(jwkJsonString)
            val verifier: JWSVerifier = ECDSAVerifier(ecKey)
            val signedJWT = SignedJWT.parse(input.jwt)
            val verified = signedJWT.verify(verifier)
            return verified
        } catch (e: Exception) {
            throw Exception("Couldn't verify the JWT Signature: ${e.message}", e)
        }
    }

    private fun JWTHeader.toJWSHeader(): JWSHeader {
        val type = typ
        return JWSHeader.Builder(JWSAlgorithm.parse(alg)).apply {
            type(JOSEObjectType(type))
            keyID(kid)
        }.build()
    }
}

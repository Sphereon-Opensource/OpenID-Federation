package com.sphereon.oid.fed.common.crypto

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.common.logging.Logger
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.ParseException

actual fun verify(jwtToken: String): Boolean {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val signedJWT = SignedJWT.parse(jwtToken)
        val payload = json.decodeFromString<EntityConfigurationStatement>(signedJWT.payload.toString())

        //Filtering key from array of jwks
        val jwkArray = payload.jwks.get("keys")?.jsonArray
        val filteredJwk = jwkArray?.filter {
            val value: String = it.jsonObject.get("kid")?.jsonPrimitive?.content ?: ""
            value == signedJWT.header.keyID
        }

        val jwk: JWK = JWK.parse(Json.encodeToString(filteredJwk?.get(0)))

        when (jwk) {
            is RSAKey -> {
                val publicKey = jwk.toRSAPublicKey()
                val verifier = RSASSAVerifier(publicKey)
                signedJWT.verify(verifier)
            }

            is ECKey -> {
                val publicKey = jwk.toECPublicKey()
                val verifier = ECDSAVerifier(publicKey)
                signedJWT.verify(verifier)
            }

            else -> false // Unsupported key type
        }
    } catch (e: ParseException) {
        Logger.error("OIDF", "Exception", e)
        false
    } catch (e: JOSEException) {
        Logger.error("OIDF", "Exception", e)
        false
    }
}
package com.sphereon.oid.fed.client.services.jwtService

import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.jsonObject


class JwtService(private val context: FederationContext) {
    suspend fun fetchAndVerifyJwt(endpoint: String, verifyWithKey: Jwk? = null): String {
        context.logger.debug("Fetching JWT from endpoint: $endpoint")
        val jwt = context.httpResolver.get(endpoint)

        if (verifyWithKey != null) {
            verifyJwt(jwt, verifyWithKey)
        } else {
            verifySelfSignedJwt(jwt)
        }

        return jwt
    }

    suspend fun verifyJwt(jwt: String, key: Jwk) {
        context.logger.debug("Verifying JWT signature with key: ${key.kid}")
        if (!context.cryptoService.verify(jwt, key)) {
            throw IllegalStateException("JWT signature verification failed")
        }
        context.logger.debug("JWT signature verified successfully")
    }

    suspend fun verifySelfSignedJwt(jwt: String) {
        context.logger.verbose("Verifying self-signed JWT", jwt)

        val decodedJwt = decodeJWTComponents(jwt)
        val kid = decodedJwt.header.kid
        context.logger.debug("Verifying self-signed JWT with kid: $kid")

        val jwksJson =
            decodedJwt.payload["jwks"]?.jsonObject ?: throw IllegalStateException("No JWKS found in JWT payload")
        context.logger.debug("Found JWKS in JWT payload: $jwksJson")

        val keysJsonArray = jwksJson["keys"].toString()
        context.logger.debug("Found 'keys' array in JWKS: $keysJsonArray")

        val jwks: Array<Jwk> = context.json.decodeFromString(keysJsonArray)

        context.logger.debug("Decoded JWKS: $jwks")


        val key = jwks.find { it.kid == kid } ?: throw IllegalStateException("No matching key found for kid: $kid")

        verifyJwt(jwt, key)
    }
}

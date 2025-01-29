package com.sphereon.oid.fed.client.services.jwtService

import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class JwtService(private val context: FederationContext) {
    /**
     * Fetches and verifies a JWT from a given endpoint
     */
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

    /**
     * Verifies a JWT signature with a given key
     */
    suspend fun verifyJwt(jwt: String, key: Jwk) {
        context.logger.debug("Verifying JWT signature with key: ${key.kid}")
        if (!context.cryptoService.verify(jwt, key)) {
            throw IllegalStateException("JWT signature verification failed")
        }
        context.logger.debug("JWT signature verified successfully")
    }

    /**
     * Verifies a JWT is self-signed using its own JWKS
     */
    suspend fun verifySelfSignedJwt(jwt: String): Jwk {
        val decodedJwt = decodeJWTComponents(jwt)
        context.logger.debug("Verifying self-signed JWT with kid: ${decodedJwt.header.kid}")

        val jwks = decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
            ?: throw IllegalStateException("No JWKS found in JWT payload")

        val key = findKeyInJwks(jwks, decodedJwt.header.kid, context.json)
            ?: throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")

        verifyJwt(jwt, key)
        return key
    }
}
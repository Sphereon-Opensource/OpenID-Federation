package com.sphereon.oid.fed.client.services.jwtService

import com.sphereon.oid.fed.client.context.FederationContext
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.openapi.models.BaseJwk
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class JwtService(private val context: FederationContext) {
    suspend fun fetchAndVerifyJwt(endpoint: String, verifyWithKey: BaseJwk? = null): String {
        context.logger.debug("Fetching JWT from endpoint: $endpoint")
        val jwt = context.httpResolver.get(endpoint)

        if (verifyWithKey != null) {
            verifyJwt(jwt, verifyWithKey)
        } else {
            verifySelfSignedJwt(jwt)
        }

        return jwt
    }

    suspend fun verifyJwt(jwt: String, key: BaseJwk) {
        context.logger.debug("Verifying JWT signature with key: ${key.kid}")
        if (!context.cryptoService.verify(jwt, key)) {
            throw IllegalStateException("JWT signature verification failed")
        }
        context.logger.debug("JWT signature verified successfully")
    }

    suspend fun verifySelfSignedJwt(jwt: String) {
        val decodedJwt = decodeJWTComponents(jwt)
        context.logger.debug("Verifying self-signed JWT with kid: ${decodedJwt.header.kid}")

        val jwks = decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray?.let { array ->
            context.json.decodeFromJsonElement(ArraySerializer(BaseJwk.serializer()), array)
        } ?: throw IllegalStateException("No JWKS found in JWT payload")

        val key = jwks.find { it.kid == decodedJwt.header.kid }
            ?: throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")

        verifyJwt(jwt, key)
    }
}
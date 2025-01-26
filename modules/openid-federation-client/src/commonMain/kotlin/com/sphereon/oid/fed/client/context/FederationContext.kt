package com.sphereon.oid.fed.client.context

import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.IFetchService
import com.sphereon.oid.fed.logger.Logger
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class FederationContext(
    val fetchService: IFetchService = fetchService(),
    val cryptoService: ICryptoService = cryptoService(),
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
) {
    private val logger = Logger.tag("sphereon:oidf:client:context")

    /**
     * Fetches and verifies a JWT from a given endpoint
     */
    suspend fun fetchAndVerifyJwt(endpoint: String, verifyWithKey: Jwk? = null): String {
        logger.debug("Fetching JWT from endpoint: $endpoint")
        val jwt = fetchService.fetchStatement(endpoint)

        if (verifyWithKey != null) {
            verifyJwt(jwt, verifyWithKey)
        }

        return jwt
    }

    /**
     * Verifies a JWT signature with a given key
     */

    suspend fun verifyJwt(jwt: String, key: Jwk) {
        logger.debug("Verifying JWT signature with key: ${key.kid}")
        if (!cryptoService.verify(jwt, key)) {
            throw IllegalStateException("JWT signature verification failed")
        }
        logger.debug("JWT signature verified successfully")
    }

    /**
     * Verifies a JWT is self-signed using its own JWKS
     */
    suspend fun verifySelfSignedJwt(jwt: String): Jwk {
        val decodedJwt = decodeJWTComponents(jwt)
        logger.debug("Verifying self-signed JWT with kid: ${decodedJwt.header.kid}")

        val jwks = decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
            ?: throw IllegalStateException("No JWKS found in JWT payload")

        val key = findKeyInJwks(jwks, decodedJwt.header.kid)
            ?: throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")

        verifyJwt(jwt, key)
        return key
    }

    /**
     * Decodes a JSON element into a specific type
     */
    fun <T> decodeJsonElement(serializer: KSerializer<T>, element: JsonElement): T {
        return json.decodeFromJsonElement(serializer, element)
    }

    /**
     * Creates a new JSON decoder with custom settings if needed
     */
    fun createJsonDecoder(
        ignoreUnknownKeys: Boolean = true,
        coerceInputValues: Boolean = true,
        isLenient: Boolean = true
    ): Json {
        return Json {
            this.ignoreUnknownKeys = ignoreUnknownKeys
            this.coerceInputValues = coerceInputValues
            this.isLenient = isLenient
        }
    }
}
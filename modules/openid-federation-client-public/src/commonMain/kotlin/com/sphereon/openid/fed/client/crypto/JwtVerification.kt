package com.sphereon.openid.fed.client.crypto

import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.crypto.resolution.extern.ExternalIdentifierJwkOpts
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.Json

/**
 * Verifies a JWT signature using IDK's JwtService with an external JWK.
 *
 * @param jwt The JWT string to verify
 * @param key The public key (JWK) to verify against
 * @return true if verification succeeded, false otherwise
 */
suspend fun JwtService.verifyJwtSignature(jwt: String, key: Jwk): Boolean {
    // Convert OpenAPI Jwk to IDK Jwk via JSON serialization
    val jwkJson = Json.encodeToString(Jwk.serializer(), key)
    val cryptoJwk: CryptoJwk = cryptoJsonSerializer.decodeFromString(CryptoJwk.serializer(), jwkJson)

    // Create verification args with external JWK
    val args = VerifyJwsArgs(
        jws = JwsCompact(jwt),
        identifier = ExternalIdentifierJwkOpts(identifier = cryptoJwk)
    )

    // Verify using IDK's JwtService
    val result = verifyJws(args)

    return result.isOk && result.value.isValid
}

/**
 * Fetches a JWT from an endpoint and optionally verifies it.
 *
 * @param endpoint The URL to fetch the JWT from
 * @param httpResolver The HTTP resolver to use for fetching
 * @param verifyWithKey Optional key to verify the JWT against
 * @return The JWT string
 * @throws IllegalStateException if verification fails
 */
suspend fun JwtService.fetchAndVerifyJwt(
    endpoint: String,
    httpResolver: com.sphereon.openid.fed.httpResolver.HttpResolver<String>,
    verifyWithKey: Jwk? = null
): String {
    val jwt = httpResolver.get(endpoint)

    if (verifyWithKey != null) {
        if (!verifyJwtSignature(jwt, verifyWithKey)) {
            throw IllegalStateException("JWT signature verification failed")
        }
    }

    return jwt
}

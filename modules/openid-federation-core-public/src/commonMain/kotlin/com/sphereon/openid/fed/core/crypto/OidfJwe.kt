package com.sphereon.openid.fed.core.crypto

/**
 * Optional JWE support for OpenID Federation payloads.
 *
 * ## Boundary
 * - Federation protocol entity statements are **JWS** by default (OpenID Federation).
 * - Encrypted statements / nested JWTs are **optional**; implement with IDK
 *   `com.sphereon.crypto.jose.jwe.JweService` (open-source crypto), not a second crypto stack.
 * - This SPI keeps OIDF free of hard JWE DI while allowing hosts to plug encryption.
 *
 * Enable via `oidf.jwe.enabled=true` when a host binds [OidfJweService].
 */
interface OidfJweService {
    /**
     * Encrypt UTF-8 plaintext to a compact JWE string.
     * @return compact JWE or error message
     */
    suspend fun encryptCompact(plaintext: String): Result<String>

    /**
     * Decrypt a compact JWE string to UTF-8 plaintext.
     */
    suspend fun decryptCompact(jweCompact: String): Result<String>
}

/**
 * No-op JWE service used when `oidf.jwe.enabled` is false or no host binding is present.
 */
object NoOpOidfJweService : OidfJweService {
    override suspend fun encryptCompact(plaintext: String): Result<String> =
        Result.failure(UnsupportedOperationException("JWE is disabled (oidf.jwe.enabled=false)"))

    override suspend fun decryptCompact(jweCompact: String): Result<String> =
        Result.failure(UnsupportedOperationException("JWE is disabled (oidf.jwe.enabled=false)"))
}

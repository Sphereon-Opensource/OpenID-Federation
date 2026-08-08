package com.sphereon.openid.fed.services.clientauth

/**
 * Replay guard for RFC 7523 / OIDC Core §9 `jti` on federation endpoint client assertions.
 *
 * Same contract as IDK `ClientAssertionJtiStore`, scoped to federation server use so we do
 * not require the full OAuth AS authorization module.
 */
interface FederationClientAssertionJtiStore {
    /**
     * @return `true` if newly recorded (accept); `false` if replay (reject).
     */
    suspend fun recordIfNew(
        clientEntityId: String,
        jti: String,
        expiresAtEpochSeconds: Long,
    ): Boolean
}

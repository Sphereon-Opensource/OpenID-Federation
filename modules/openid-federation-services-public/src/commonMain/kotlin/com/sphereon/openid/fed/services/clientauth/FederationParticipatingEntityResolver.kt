package com.sphereon.openid.fed.services.clientauth

import com.sphereon.openid.fed.core.error.FederationResult

/**
 * Federation analogue of OAuth AS ClientRegistry + ClientJwksResolver for §8.8.
 *
 * Resolves a client **Entity Identifier** to Federation Entity Keys only when the
 * entity is accepted under the configured [com.sphereon.openid.fed.core.config.FederationClientAuthMembershipPolicy].
 */
interface FederationParticipatingEntityResolver {
    /**
     * Resolve and authorize a client entity for private_key_jwt endpoint authentication.
     *
     * @return [FederationParticipatingEntity] with Federation Entity Keys, or an error
     *   (typically mapped to `invalid_client` at the HTTP layer).
     */
    suspend fun resolve(args: ResolveParticipatingEntityArgs): FederationResult<FederationParticipatingEntity>
}

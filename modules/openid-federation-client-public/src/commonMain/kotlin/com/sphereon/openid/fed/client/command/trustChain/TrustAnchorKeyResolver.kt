package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk

/**
 * The public keys a Trust Anchor is trusted with when no keys are pinned for it (OpenID Federation 1.1 §10, §11.2):
 * the keys of the Entity Configuration the anchor publishes about itself at its own https Entity Identifier,
 * self-signed and current. Pinned keys are the caller's choice and are passed alongside, never looked up here.
 */
interface TrustAnchorKeyResolver {
    suspend fun publishedKeys(trustAnchor: String, currentTimeSeconds: Long): IdkResult<List<Jwk>, FederationError>
}

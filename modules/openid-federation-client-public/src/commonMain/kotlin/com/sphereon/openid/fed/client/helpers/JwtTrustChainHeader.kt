package com.sphereon.openid.fed.client.helpers

import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader

/**
 * Helpers for the OpenID Federation / OpenID4VCI JOSE header parameter `trust_chain`
 * (OIDFed 1.1 §4.3, OpenID4VCI Appendix F.1, wallet architecture §8.4).
 *
 * Use with [com.sphereon.openid.fed.client.FederationClient.trustChainVerify] or the wallet
 * [verifyOfflineTrustChain] path after extracting the chain from a compact JWT or [Jwt] model.
 */
object JwtTrustChainHeader {

    /**
     * Extract `trust_chain` from a typed [JwtHeader], if present and non-empty.
     */
    fun extract(header: JwtHeader): List<String>? {
        val chain = header.trustChain
        return if (chain.isNullOrEmpty()) null else chain
    }

    /**
     * Extract `trust_chain` from a decoded [Jwt].
     */
    fun extract(jwt: Jwt): List<String>? = extract(jwt.header)

    /**
     * Decode a compact JWT (header.payload.signature) and extract `trust_chain`.
     *
     * @return non-empty list of entity-statement JWTs, or null if absent/empty
     * @throws Exception if the compact JWT cannot be decoded (same as [decodeJWTComponents])
     */
    fun extractFromCompactJwt(compactJwt: String): List<String>? =
        extract(decodeJWTComponents(compactJwt))

    /**
     * Extract optional `peer_trust_chain` header (entity-to-entity).
     */
    fun extractPeer(header: JwtHeader): List<String>? {
        val chain = header.peerTrustChain
        return if (chain.isNullOrEmpty()) null else chain
    }

    fun extractPeer(jwt: Jwt): List<String>? = extractPeer(jwt.header)

    fun extractPeerFromCompactJwt(compactJwt: String): List<String>? =
        extractPeer(decodeJWTComponents(compactJwt))

    /**
     * Result of reading both trust chain headers from a JWT.
     */
    data class Extracted(
        val trustChain: List<String>?,
        val peerTrustChain: List<String>?,
    ) {
        val hasAny: Boolean get() = !trustChain.isNullOrEmpty() || !peerTrustChain.isNullOrEmpty()
    }

    fun extractAll(jwt: Jwt): Extracted =
        Extracted(trustChain = extract(jwt), peerTrustChain = extractPeer(jwt))

    fun extractAllFromCompactJwt(compactJwt: String): Extracted =
        extractAll(decodeJWTComponents(compactJwt))
}

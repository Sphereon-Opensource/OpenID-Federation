package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.ResolveResponse

/**
 * Service interface for resolving entities and verifying trust chains, metadata, and trust marks.
 *
 * This interface defines operations to fetch entity configuration statements, resolve trust chains,
 * and filter metadata. It also verifies trust marks issued by authorized entities.
 *
 * This service aggregates all resolution-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface ResolutionService {

    /**
     * Resolves entity metadata and Trust Marks under the given Trust Anchor(s).
     *
     * @param tenantId Tenant performing resolution (signer of the resolve response).
     * @param sub Entity Identifier of the subject.
     * @param trustAnchors Trust Anchor Entity Identifiers in preference order
     *   (OIDFed 1.1 §8.3 — request parameter may be repeated).
     * @param entityTypes Optional Entity Type Identifiers to include (may be repeated on the wire).
     */
    suspend fun resolveEntity(
        tenantId: String,
        sub: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>?
    ): FederationResult<ResolveResponse>

    /**
     * Same as [resolveEntity] then signs the response as `resolve-response+jwt`.
     */
    suspend fun getSignedResolveResponseJwt(
        tenantId: String,
        sub: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>?
    ): FederationResult<String>
}

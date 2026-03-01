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
     * Resolves and retrieves information for a specified entity based on the given parameters,
     * including trust chain resolution, metadata filtering, and trust mark verification.
     *
     * @param account The account information of the user initiating the resolution.
     * @param sub The entity identifier (subject) whose information is to be resolved.
     * @param trustAnchor The trust anchor against which the entity's trust chain is validated.
     * @param entityTypes Array of entity types used for filtering metadata; can be null to include all types.
     * @return FederationResult containing the ResolveResponse or an error.
     */
    suspend fun resolveEntity(
        tenantId: String,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<ResolveResponse>

    /**
     * Resolves an entity and returns a signed JWT containing the resolve response.
     *
     * @param account The account information of the user initiating the resolution.
     * @param sub The entity identifier (subject) whose information is to be resolved.
     * @param trustAnchor The trust anchor against which the entity's trust chain is validated.
     * @param entityTypes Array of entity types used for filtering metadata; can be null to include all types.
     * @return FederationResult containing the signed JWT or an error.
     */
    suspend fun getSignedResolveResponseJwt(
        tenantId: String,
        sub: String,
        trustAnchor: String,
        entityTypes: Array<String>?
    ): FederationResult<String>
}

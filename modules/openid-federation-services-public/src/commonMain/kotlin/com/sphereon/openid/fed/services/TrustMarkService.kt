package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

/**
 * Service interface responsible for managing Trust Marks, their types, and issuers.
 * Provides operations such as creation, retrieval, deletion, and management
 * of Trust Marks and associated types.
 *
 * This service aggregates all trust mark-related commands and provides
 * direct method access.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface TrustMarkService {

    suspend fun createTrustMarkType(tenantId: String, createDto: CreateTrustMarkType): FederationResult<TrustMarkType>
    suspend fun findAllByAccount(tenantId: String): FederationResult<List<TrustMarkType>>
    suspend fun findById(tenantId: String, id: String): FederationResult<TrustMarkType>
    suspend fun deleteTrustMarkType(tenantId: String, id: String): FederationResult<TrustMarkType>
    suspend fun getIssuersForTrustMarkType(tenantId: String, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>>
    suspend fun addIssuerToTrustMarkType(tenantId: String, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer>
    suspend fun removeIssuerFromTrustMarkType(tenantId: String, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer>
    suspend fun getTrustMarksForAccount(tenantId: String): FederationResult<List<TrustMark>>
    suspend fun createTrustMark(tenantId: String, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult>
    suspend fun deleteTrustMark(tenantId: String, id: String): FederationResult<TrustMarkEntity>
    suspend fun getTrustMarkStatus(tenantId: String, request: TrustMarkStatusRequest): FederationResult<Boolean>
    /**
     * Build signed Trust Mark Status Response JWT (OIDFed 1.1 §8.4).
     *
     * @param trustMarkJwt REQUIRED full Trust Mark JWT from the status request (`trust_mark` param).
     *   When non-null, status is evaluated for **this** JWT (exact match / revocation / exp).
     *   When null, falls back to [request] sub + trust_mark_type (+ optional iat).
     */
    suspend fun getSignedTrustMarkStatusJwt(
        tenantId: String,
        request: TrustMarkStatusRequest,
        trustMarkJwt: String? = null,
    ): FederationResult<String>
    suspend fun getTrustMarkedSubs(tenantId: String, request: TrustMarkListRequest): FederationResult<Array<String>>
    suspend fun getTrustMark(tenantId: String, request: TrustMarkRequest): FederationResult<String>
}

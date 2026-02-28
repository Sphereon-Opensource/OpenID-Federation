package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
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

    suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): FederationResult<TrustMarkType>
    suspend fun findAllByAccount(account: Account): FederationResult<List<TrustMarkType>>
    suspend fun findById(account: Account, id: String): FederationResult<TrustMarkType>
    suspend fun deleteTrustMarkType(account: Account, id: String): FederationResult<TrustMarkType>
    suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>>
    suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer>
    suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer>
    suspend fun getTrustMarksForAccount(account: Account): FederationResult<List<TrustMark>>
    suspend fun createTrustMark(account: Account, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult>
    suspend fun deleteTrustMark(account: Account, id: String): FederationResult<TrustMarkEntity>
    suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): FederationResult<Boolean>
    suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): FederationResult<Array<String>>
    suspend fun getTrustMark(account: Account, request: TrustMarkRequest): FederationResult<String>
}

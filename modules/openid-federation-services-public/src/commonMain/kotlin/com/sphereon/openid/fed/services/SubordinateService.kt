package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult

import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonElement

/**
 * Service interface for managing subordinate entities, their associated data,
 * and their interactions with an account.
 *
 * This service provides methods to handle CRUD operations for subordinates,
 * manage subordinate-related keys (JWKs), metadata, and statements.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface SubordinateService {

    suspend fun findSubordinatesByAccount(tenantId: String): FederationResult<Array<Subordinate>>
    suspend fun findSubordinatesByAccountAsArray(tenantId: String): FederationResult<Array<String>>
    suspend fun deleteSubordinate(tenantId: String, id: String): FederationResult<Subordinate>
    suspend fun createSubordinate(tenantId: String, subordinateDTO: CreateSubordinate): FederationResult<Subordinate>
    suspend fun getSubordinateStatement(tenantId: String, id: String): FederationResult<SubordinateStatement>
    suspend fun publishSubordinateStatement(
        tenantId: String,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String>
    suspend fun fetchSubordinateStatement(iss: String, sub: String): FederationResult<String>
    suspend fun createSubordinateJwk(tenantId: String, id: String, jwk: Jwk): FederationResult<SubordinateJwk>
    suspend fun getSubordinateJwks(tenantId: String, id: String): FederationResult<Array<SubordinateJwk>>
    suspend fun deleteSubordinateJwk(tenantId: String, id: String, jwkId: String): FederationResult<SubordinateJwk>
    suspend fun findSubordinateMetadata(tenantId: String, subordinateId: String): FederationResult<Array<SubordinateMetadata>>
    suspend fun createMetadata(
        tenantId: String,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): FederationResult<SubordinateMetadata>
    suspend fun deleteSubordinateMetadata(tenantId: String, subordinateId: String, id: String): FederationResult<SubordinateMetadata>
}

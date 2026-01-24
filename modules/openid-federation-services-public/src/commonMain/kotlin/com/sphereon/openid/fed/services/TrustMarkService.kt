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
import com.sphereon.openid.fed.services.command.trustMark.*
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

/**
 * Service interface responsible for managing Trust Marks, their types, and issuers.
 * Provides operations such as creation, retrieval, deletion, and management
 * of Trust Marks and associated types.
 *
 * This service aggregates all trust mark-related commands and provides both
 * direct method access and command-based access patterns.
 *
 * All methods return FederationResult for type-safe error handling.
 */
interface TrustMarkService :
    CreateTrustMarkTypeCommandService,
    FindAllTrustMarkTypesByAccountCommandService,
    FindTrustMarkTypeByIdCommandService,
    DeleteTrustMarkTypeCommandService,
    GetIssuersForTrustMarkTypeCommandService,
    AddIssuerToTrustMarkTypeCommandService,
    RemoveIssuerFromTrustMarkTypeCommandService,
    GetTrustMarksForAccountCommandService,
    CreateTrustMarkCommandService,
    DeleteTrustMarkCommandService,
    GetTrustMarkStatusCommandService,
    GetTrustMarkedSubsCommandService,
    GetTrustMarkCommandService {

    val commands: Commands

    interface Commands {
        val createTrustMarkType: CreateTrustMarkTypeCommand
        val findAllByAccount: FindAllTrustMarkTypesByAccountCommand
        val findById: FindTrustMarkTypeByIdCommand
        val deleteTrustMarkType: DeleteTrustMarkTypeCommand
        val getIssuersForTrustMarkType: GetIssuersForTrustMarkTypeCommand
        val addIssuerToTrustMarkType: AddIssuerToTrustMarkTypeCommand
        val removeIssuerFromTrustMarkType: RemoveIssuerFromTrustMarkTypeCommand
        val getTrustMarksForAccount: GetTrustMarksForAccountCommand
        val createTrustMark: CreateTrustMarkCommand
        val deleteTrustMark: DeleteTrustMarkCommand
        val getTrustMarkStatus: GetTrustMarkStatusCommand
        val getTrustMarkedSubs: GetTrustMarkedSubsCommand
        val getTrustMark: GetTrustMarkCommand
    }

    override suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): FederationResult<TrustMarkType>
    override suspend fun findAllByAccount(account: Account): FederationResult<List<TrustMarkType>>
    override suspend fun findById(account: Account, id: String): FederationResult<TrustMarkType>
    override suspend fun deleteTrustMarkType(account: Account, id: String): FederationResult<TrustMarkType>
    override suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>>
    override suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer>
    override suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer>
    override suspend fun getTrustMarksForAccount(account: Account): FederationResult<List<TrustMark>>
    override suspend fun createTrustMark(account: Account, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult>
    override suspend fun deleteTrustMark(account: Account, id: String): FederationResult<TrustMarkEntity>
    override suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): FederationResult<Boolean>
    override suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): FederationResult<Array<String>>
    override suspend fun getTrustMark(account: Account, request: TrustMarkRequest): FederationResult<String>
}

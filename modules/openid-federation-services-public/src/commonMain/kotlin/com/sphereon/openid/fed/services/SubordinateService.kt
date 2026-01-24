package com.sphereon.openid.fed.services

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.services.command.subordinate.*
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
interface SubordinateService :
    FindSubordinatesByAccountCommandService,
    FindSubordinatesByAccountAsArrayCommandService,
    DeleteSubordinateCommandService,
    CreateSubordinateCommandService,
    GetSubordinateStatementCommandService,
    PublishSubordinateStatementCommandService,
    FetchSubordinateStatementCommandService,
    CreateSubordinateJwkCommandService,
    GetSubordinateJwksCommandService,
    DeleteSubordinateJwkCommandService,
    FindSubordinateMetadataCommandService,
    CreateSubordinateMetadataCommandService,
    DeleteSubordinateMetadataCommandService {

    val commands: Commands

    interface Commands {
        val findSubordinatesByAccount: FindSubordinatesByAccountCommand
        val findSubordinatesByAccountAsArray: FindSubordinatesByAccountAsArrayCommand
        val deleteSubordinate: DeleteSubordinateCommand
        val createSubordinate: CreateSubordinateCommand
        val getSubordinateStatement: GetSubordinateStatementCommand
        val publishSubordinateStatement: PublishSubordinateStatementCommand
        val fetchSubordinateStatement: FetchSubordinateStatementCommand
        val createSubordinateJwk: CreateSubordinateJwkCommand
        val getSubordinateJwks: GetSubordinateJwksCommand
        val deleteSubordinateJwk: DeleteSubordinateJwkCommand
        val findSubordinateMetadata: FindSubordinateMetadataCommand
        val createMetadata: CreateSubordinateMetadataCommand
        val deleteSubordinateMetadata: DeleteSubordinateMetadataCommand
    }

    override suspend fun findSubordinatesByAccount(account: Account): FederationResult<Array<Subordinate>>
    override suspend fun findSubordinatesByAccountAsArray(account: Account): FederationResult<Array<String>>
    override suspend fun deleteSubordinate(account: Account, id: String): FederationResult<Subordinate>
    override suspend fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): FederationResult<Subordinate>
    override suspend fun getSubordinateStatement(account: Account, id: String): FederationResult<SubordinateStatement>
    override suspend fun publishSubordinateStatement(
        account: Account,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String>
    override suspend fun fetchSubordinateStatement(iss: String, sub: String): FederationResult<String>
    override suspend fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): FederationResult<SubordinateJwk>
    override suspend fun getSubordinateJwks(account: Account, id: String): FederationResult<Array<SubordinateJwk>>
    override suspend fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): FederationResult<SubordinateJwk>
    override suspend fun findSubordinateMetadata(account: Account, subordinateId: String): FederationResult<Array<SubordinateMetadata>>
    override suspend fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): FederationResult<SubordinateMetadata>
    override suspend fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): FederationResult<SubordinateMetadata>
}

package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.services.command.subordinate.*
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of SubordinateService that aggregates all subordinate-related commands.
 *
 * All methods delegate to their corresponding command implementations,
 * providing a unified service interface while maintaining the command pattern.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SubordinateService>())
class SubordinateServiceImpl(
    private val findSubordinatesByAccountCommand: FindSubordinatesByAccountCommand,
    private val findSubordinatesByAccountAsArrayCommand: FindSubordinatesByAccountAsArrayCommand,
    private val deleteSubordinateCommand: DeleteSubordinateCommand,
    private val createSubordinateCommand: CreateSubordinateCommand,
    private val getSubordinateStatementCommand: GetSubordinateStatementCommand,
    private val publishSubordinateStatementCommand: PublishSubordinateStatementCommand,
    private val fetchSubordinateStatementCommand: FetchSubordinateStatementCommand,
    private val createSubordinateJwkCommand: CreateSubordinateJwkCommand,
    private val getSubordinateJwksCommand: GetSubordinateJwksCommand,
    private val deleteSubordinateJwkCommand: DeleteSubordinateJwkCommand,
    private val findSubordinateMetadataCommand: FindSubordinateMetadataCommand,
    private val createMetadataCommand: CreateSubordinateMetadataCommand,
    private val deleteSubordinateMetadataCommand: DeleteSubordinateMetadataCommand
) : SubordinateService {

    override suspend fun findSubordinatesByAccount(tenantId: String): FederationResult<Array<Subordinate>> =
        findSubordinatesByAccountCommand.execute(FindSubordinatesByAccountArgs(tenantId)).toFederationResult()

    override suspend fun findSubordinatesByAccountAsArray(tenantId: String): FederationResult<Array<String>> =
        findSubordinatesByAccountAsArrayCommand.execute(FindSubordinatesByAccountAsArrayArgs(tenantId)).toFederationResult()

    override suspend fun deleteSubordinate(tenantId: String, id: String): FederationResult<Subordinate> =
        deleteSubordinateCommand.execute(DeleteSubordinateArgs(tenantId, id)).toFederationResult()

    override suspend fun createSubordinate(tenantId: String, subordinateDTO: CreateSubordinate): FederationResult<Subordinate> =
        createSubordinateCommand.execute(CreateSubordinateArgs(tenantId, subordinateDTO)).toFederationResult()

    override suspend fun getSubordinateStatement(tenantId: String, id: String): FederationResult<SubordinateStatement> =
        getSubordinateStatementCommand.execute(GetSubordinateStatementArgs(tenantId, id)).toFederationResult()

    override suspend fun publishSubordinateStatement(
        tenantId: String,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String> =
        publishSubordinateStatementCommand.execute(PublishSubordinateStatementArgs(tenantId, id, dryRun, kmsKeyRef, kid)).toFederationResult()

    override suspend fun fetchSubordinateStatement(iss: String, sub: String): FederationResult<String> =
        fetchSubordinateStatementCommand.execute(FetchSubordinateStatementArgs(iss, sub)).toFederationResult()

    override suspend fun createSubordinateJwk(tenantId: String, id: String, jwk: Jwk): FederationResult<SubordinateJwk> =
        createSubordinateJwkCommand.execute(CreateSubordinateJwkArgs(tenantId, id, jwk)).toFederationResult()

    override suspend fun getSubordinateJwks(tenantId: String, id: String): FederationResult<Array<SubordinateJwk>> =
        getSubordinateJwksCommand.execute(GetSubordinateJwksArgs(tenantId, id)).toFederationResult()

    override suspend fun deleteSubordinateJwk(tenantId: String, id: String, jwkId: String): FederationResult<SubordinateJwk> =
        deleteSubordinateJwkCommand.execute(DeleteSubordinateJwkArgs(tenantId, id, jwkId)).toFederationResult()

    override suspend fun findSubordinateMetadata(tenantId: String, subordinateId: String): FederationResult<Array<SubordinateMetadata>> =
        findSubordinateMetadataCommand.execute(FindSubordinateMetadataArgs(tenantId, subordinateId)).toFederationResult()

    override suspend fun createMetadata(
        tenantId: String,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): FederationResult<SubordinateMetadata> =
        createMetadataCommand.execute(CreateSubordinateMetadataArgs(tenantId, subordinateId, key, metadata)).toFederationResult()

    override suspend fun deleteSubordinateMetadata(tenantId: String, subordinateId: String, id: String): FederationResult<SubordinateMetadata> =
        deleteSubordinateMetadataCommand.execute(DeleteSubordinateMetadataArgs(tenantId, subordinateId, id)).toFederationResult()
}

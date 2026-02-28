package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateSubordinate
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Subordinate
import com.sphereon.openid.fed.openapi.models.SubordinateJwk
import com.sphereon.openid.fed.openapi.models.SubordinateMetadata
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.services.command.subordinate.*
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of SubordinateService that aggregates all subordinate-related commands.
 *
 * All methods delegate to their corresponding command implementations,
 * providing a unified service interface while maintaining the command pattern.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = SubordinateService::class)
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

    override suspend fun findSubordinatesByAccount(account: Account): FederationResult<Array<Subordinate>> =
        findSubordinatesByAccountCommand.execute(FindSubordinatesByAccountArgs(account)).toFederationResult()

    override suspend fun findSubordinatesByAccountAsArray(account: Account): FederationResult<Array<String>> =
        findSubordinatesByAccountAsArrayCommand.execute(FindSubordinatesByAccountAsArrayArgs(account)).toFederationResult()

    override suspend fun deleteSubordinate(account: Account, id: String): FederationResult<Subordinate> =
        deleteSubordinateCommand.execute(DeleteSubordinateArgs(account, id)).toFederationResult()

    override suspend fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): FederationResult<Subordinate> =
        createSubordinateCommand.execute(CreateSubordinateArgs(account, subordinateDTO)).toFederationResult()

    override suspend fun getSubordinateStatement(account: Account, id: String): FederationResult<SubordinateStatement> =
        getSubordinateStatementCommand.execute(GetSubordinateStatementArgs(account, id)).toFederationResult()

    override suspend fun publishSubordinateStatement(
        account: Account,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String> =
        publishSubordinateStatementCommand.execute(PublishSubordinateStatementArgs(account, id, dryRun, kmsKeyRef, kid)).toFederationResult()

    override suspend fun fetchSubordinateStatement(iss: String, sub: String): FederationResult<String> =
        fetchSubordinateStatementCommand.execute(FetchSubordinateStatementArgs(iss, sub)).toFederationResult()

    override suspend fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): FederationResult<SubordinateJwk> =
        createSubordinateJwkCommand.execute(CreateSubordinateJwkArgs(account, id, jwk)).toFederationResult()

    override suspend fun getSubordinateJwks(account: Account, id: String): FederationResult<Array<SubordinateJwk>> =
        getSubordinateJwksCommand.execute(GetSubordinateJwksArgs(account, id)).toFederationResult()

    override suspend fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): FederationResult<SubordinateJwk> =
        deleteSubordinateJwkCommand.execute(DeleteSubordinateJwkArgs(account, id, jwkId)).toFederationResult()

    override suspend fun findSubordinateMetadata(account: Account, subordinateId: String): FederationResult<Array<SubordinateMetadata>> =
        findSubordinateMetadataCommand.execute(FindSubordinateMetadataArgs(account, subordinateId)).toFederationResult()

    override suspend fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): FederationResult<SubordinateMetadata> =
        createMetadataCommand.execute(CreateSubordinateMetadataArgs(account, subordinateId, key, metadata)).toFederationResult()

    override suspend fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): FederationResult<SubordinateMetadata> =
        deleteSubordinateMetadataCommand.execute(DeleteSubordinateMetadataArgs(account, subordinateId, id)).toFederationResult()
}

package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
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

    inner class CommandsImpl : SubordinateService.Commands {
        override val findSubordinatesByAccount get() = findSubordinatesByAccountCommand
        override val findSubordinatesByAccountAsArray get() = findSubordinatesByAccountAsArrayCommand
        override val deleteSubordinate get() = deleteSubordinateCommand
        override val createSubordinate get() = createSubordinateCommand
        override val getSubordinateStatement get() = getSubordinateStatementCommand
        override val publishSubordinateStatement get() = publishSubordinateStatementCommand
        override val fetchSubordinateStatement get() = fetchSubordinateStatementCommand
        override val createSubordinateJwk get() = createSubordinateJwkCommand
        override val getSubordinateJwks get() = getSubordinateJwksCommand
        override val deleteSubordinateJwk get() = deleteSubordinateJwkCommand
        override val findSubordinateMetadata get() = findSubordinateMetadataCommand
        override val createMetadata get() = createMetadataCommand
        override val deleteSubordinateMetadata get() = deleteSubordinateMetadataCommand
    }

    override val commands: SubordinateService.Commands = CommandsImpl()

    override suspend fun findSubordinatesByAccount(account: Account): FederationResult<Array<Subordinate>> =
        findSubordinatesByAccountCommand.findSubordinatesByAccount(account)

    override suspend fun findSubordinatesByAccountAsArray(account: Account): FederationResult<Array<String>> =
        findSubordinatesByAccountAsArrayCommand.findSubordinatesByAccountAsArray(account)

    override suspend fun deleteSubordinate(account: Account, id: String): FederationResult<Subordinate> =
        deleteSubordinateCommand.deleteSubordinate(account, id)

    override suspend fun createSubordinate(account: Account, subordinateDTO: CreateSubordinate): FederationResult<Subordinate> =
        createSubordinateCommand.createSubordinate(account, subordinateDTO)

    override suspend fun getSubordinateStatement(account: Account, id: String): FederationResult<SubordinateStatement> =
        getSubordinateStatementCommand.getSubordinateStatement(account, id)

    override suspend fun publishSubordinateStatement(
        account: Account,
        id: String,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): FederationResult<String> =
        publishSubordinateStatementCommand.publishSubordinateStatement(account, id, dryRun, kmsKeyRef, kid)

    override suspend fun fetchSubordinateStatement(iss: String, sub: String): FederationResult<String> =
        fetchSubordinateStatementCommand.fetchSubordinateStatement(iss, sub)

    override suspend fun createSubordinateJwk(account: Account, id: String, jwk: Jwk): FederationResult<SubordinateJwk> =
        createSubordinateJwkCommand.createSubordinateJwk(account, id, jwk)

    override suspend fun getSubordinateJwks(account: Account, id: String): FederationResult<Array<SubordinateJwk>> =
        getSubordinateJwksCommand.getSubordinateJwks(account, id)

    override suspend fun deleteSubordinateJwk(account: Account, id: String, jwkId: String): FederationResult<SubordinateJwk> =
        deleteSubordinateJwkCommand.deleteSubordinateJwk(account, id, jwkId)

    override suspend fun findSubordinateMetadata(account: Account, subordinateId: String): FederationResult<Array<SubordinateMetadata>> =
        findSubordinateMetadataCommand.findSubordinateMetadata(account, subordinateId)

    override suspend fun createMetadata(
        account: Account,
        subordinateId: String,
        key: String,
        metadata: JsonElement
    ): FederationResult<SubordinateMetadata> =
        createMetadataCommand.createMetadata(account, subordinateId, key, metadata)

    override suspend fun deleteSubordinateMetadata(account: Account, subordinateId: String, id: String): FederationResult<SubordinateMetadata> =
        deleteSubordinateMetadataCommand.deleteSubordinateMetadata(account, subordinateId, id)
}

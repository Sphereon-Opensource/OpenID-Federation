package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
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
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = TrustMarkService::class)
class TrustMarkServiceImpl(
    private val createTrustMarkTypeCommand: CreateTrustMarkTypeCommand,
    private val findAllTrustMarkTypesByAccountCommand: FindAllTrustMarkTypesByAccountCommand,
    private val findTrustMarkTypeByIdCommand: FindTrustMarkTypeByIdCommand,
    private val deleteTrustMarkTypeCommand: DeleteTrustMarkTypeCommand,
    private val getIssuersForTrustMarkTypeCommand: GetIssuersForTrustMarkTypeCommand,
    private val addIssuerToTrustMarkTypeCommand: AddIssuerToTrustMarkTypeCommand,
    private val removeIssuerFromTrustMarkTypeCommand: RemoveIssuerFromTrustMarkTypeCommand,
    private val getTrustMarksForAccountCommand: GetTrustMarksForAccountCommand,
    private val createTrustMarkCommand: CreateTrustMarkCommand,
    private val deleteTrustMarkCommand: DeleteTrustMarkCommand,
    private val getTrustMarkStatusCommand: GetTrustMarkStatusCommand,
    private val getTrustMarkedSubsCommand: GetTrustMarkedSubsCommand,
    private val getTrustMarkCommand: GetTrustMarkCommand
) : TrustMarkService {

    inner class CommandsImpl : TrustMarkService.Commands {
        override val createTrustMarkType get() = createTrustMarkTypeCommand
        override val findAllByAccount get() = findAllTrustMarkTypesByAccountCommand
        override val findById get() = findTrustMarkTypeByIdCommand
        override val deleteTrustMarkType get() = deleteTrustMarkTypeCommand
        override val getIssuersForTrustMarkType get() = getIssuersForTrustMarkTypeCommand
        override val addIssuerToTrustMarkType get() = addIssuerToTrustMarkTypeCommand
        override val removeIssuerFromTrustMarkType get() = removeIssuerFromTrustMarkTypeCommand
        override val getTrustMarksForAccount get() = getTrustMarksForAccountCommand
        override val createTrustMark get() = createTrustMarkCommand
        override val deleteTrustMark get() = deleteTrustMarkCommand
        override val getTrustMarkStatus get() = getTrustMarkStatusCommand
        override val getTrustMarkedSubs get() = getTrustMarkedSubsCommand
        override val getTrustMark get() = getTrustMarkCommand
    }

    override val commands: TrustMarkService.Commands = CommandsImpl()

    override suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): FederationResult<TrustMarkType> =
        createTrustMarkTypeCommand.createTrustMarkType(account, createDto)

    override suspend fun findAllByAccount(account: Account): FederationResult<List<TrustMarkType>> =
        findAllTrustMarkTypesByAccountCommand.findAllByAccount(account)

    override suspend fun findById(account: Account, id: String): FederationResult<TrustMarkType> =
        findTrustMarkTypeByIdCommand.findById(account, id)

    override suspend fun deleteTrustMarkType(account: Account, id: String): FederationResult<TrustMarkType> =
        deleteTrustMarkTypeCommand.deleteTrustMarkType(account, id)

    override suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>> =
        getIssuersForTrustMarkTypeCommand.getIssuersForTrustMarkType(account, trustMarkTypeId)

    override suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer> =
        addIssuerToTrustMarkTypeCommand.addIssuerToTrustMarkType(account, trustMarkTypeId, issuerIdentifier)

    override suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer> =
        removeIssuerFromTrustMarkTypeCommand.removeIssuerFromTrustMarkType(account, trustMarkTypeId, issuerId)

    override suspend fun getTrustMarksForAccount(account: Account): FederationResult<List<TrustMark>> =
        getTrustMarksForAccountCommand.getTrustMarksForAccount(account)

    override suspend fun createTrustMark(account: Account, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult> =
        createTrustMarkCommand.createTrustMark(account, body, currentTimeMillis)

    override suspend fun deleteTrustMark(account: Account, id: String): FederationResult<TrustMarkEntity> =
        deleteTrustMarkCommand.deleteTrustMark(account, id)

    override suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): FederationResult<Boolean> =
        getTrustMarkStatusCommand.getTrustMarkStatus(account, request)

    override suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): FederationResult<Array<String>> =
        getTrustMarkedSubsCommand.getTrustMarkedSubs(account, request)

    override suspend fun getTrustMark(account: Account, request: TrustMarkRequest): FederationResult<String> =
        getTrustMarkCommand.getTrustMark(account, request)
}

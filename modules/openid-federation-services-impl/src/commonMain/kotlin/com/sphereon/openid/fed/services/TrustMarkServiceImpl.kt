package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult
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

    override suspend fun createTrustMarkType(account: Account, createDto: CreateTrustMarkType): FederationResult<TrustMarkType> =
        createTrustMarkTypeCommand.execute(CreateTrustMarkTypeArgs(account, createDto)).toFederationResult()

    override suspend fun findAllByAccount(account: Account): FederationResult<List<TrustMarkType>> =
        findAllTrustMarkTypesByAccountCommand.execute(FindAllTrustMarkTypesByAccountArgs(account)).toFederationResult()

    override suspend fun findById(account: Account, id: String): FederationResult<TrustMarkType> =
        findTrustMarkTypeByIdCommand.execute(FindTrustMarkTypeByIdArgs(account, id)).toFederationResult()

    override suspend fun deleteTrustMarkType(account: Account, id: String): FederationResult<TrustMarkType> =
        deleteTrustMarkTypeCommand.execute(DeleteTrustMarkTypeArgs(account, id)).toFederationResult()

    override suspend fun getIssuersForTrustMarkType(account: Account, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>> =
        getIssuersForTrustMarkTypeCommand.execute(GetIssuersForTrustMarkTypeArgs(account, trustMarkTypeId)).toFederationResult()

    override suspend fun addIssuerToTrustMarkType(account: Account, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer> =
        addIssuerToTrustMarkTypeCommand.execute(AddIssuerToTrustMarkTypeArgs(account, trustMarkTypeId, issuerIdentifier)).toFederationResult()

    override suspend fun removeIssuerFromTrustMarkType(account: Account, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer> =
        removeIssuerFromTrustMarkTypeCommand.execute(RemoveIssuerFromTrustMarkTypeArgs(account, trustMarkTypeId, issuerId)).toFederationResult()

    override suspend fun getTrustMarksForAccount(account: Account): FederationResult<List<TrustMark>> =
        getTrustMarksForAccountCommand.execute(GetTrustMarksForAccountArgs(account)).toFederationResult()

    override suspend fun createTrustMark(account: Account, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult> =
        createTrustMarkCommand.execute(CreateTrustMarkArgs(account, body, currentTimeMillis)).toFederationResult()

    override suspend fun deleteTrustMark(account: Account, id: String): FederationResult<TrustMarkEntity> =
        deleteTrustMarkCommand.execute(DeleteTrustMarkArgs(account, id)).toFederationResult()

    override suspend fun getTrustMarkStatus(account: Account, request: TrustMarkStatusRequest): FederationResult<Boolean> =
        getTrustMarkStatusCommand.execute(GetTrustMarkStatusArgs(account, request)).toFederationResult()

    override suspend fun getTrustMarkedSubs(account: Account, request: TrustMarkListRequest): FederationResult<Array<String>> =
        getTrustMarkedSubsCommand.execute(GetTrustMarkedSubsArgs(account, request)).toFederationResult()

    override suspend fun getTrustMark(account: Account, request: TrustMarkRequest): FederationResult<String> =
        getTrustMarkCommand.execute(GetTrustMarkArgs(account, request)).toFederationResult()
}

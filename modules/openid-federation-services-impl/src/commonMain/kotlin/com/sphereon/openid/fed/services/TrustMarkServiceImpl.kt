package com.sphereon.openid.fed.services

import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.KeyNotFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.andThenSuspend
import com.sphereon.openid.fed.core.error.toErr
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkResult
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.openid.fed.services.command.trustMark.AddIssuerToTrustMarkTypeArgs
import com.sphereon.openid.fed.services.command.trustMark.AddIssuerToTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkArgs
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkCommand
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkTypeArgs
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkArgs
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkCommand
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkTypeArgs
import com.sphereon.openid.fed.services.command.trustMark.DeleteTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.FindAllTrustMarkTypesByAccountArgs
import com.sphereon.openid.fed.services.command.trustMark.FindAllTrustMarkTypesByAccountCommand
import com.sphereon.openid.fed.services.command.trustMark.FindTrustMarkTypeByIdArgs
import com.sphereon.openid.fed.services.command.trustMark.FindTrustMarkTypeByIdCommand
import com.sphereon.openid.fed.services.command.trustMark.GetIssuersForTrustMarkTypeArgs
import com.sphereon.openid.fed.services.command.trustMark.GetIssuersForTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkArgs
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkCommand
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkStatusArgs
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkStatusCommand
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkedSubsArgs
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarkedSubsCommand
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarksForAccountArgs
import com.sphereon.openid.fed.services.command.trustMark.GetTrustMarksForAccountCommand
import com.sphereon.openid.fed.services.command.trustMark.RemoveIssuerFromTrustMarkTypeArgs
import com.sphereon.openid.fed.services.command.trustMark.RemoveIssuerFromTrustMarkTypeCommand
import com.sphereon.openid.fed.services.command.trustMark.TrustMarkStatusValue
import kotlinx.serialization.Serializable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn
import com.sphereon.openid.fed.persistence.models.TrustMark as TrustMarkEntity

/**
 * Payload for the Trust Mark Status Response JWT per OpenID Federation 1.1 Section 8.4.
 */
@Serializable
private data class TrustMarkStatusResponsePayload(
    val iss: String,
    val iat: Int,
    val trust_mark: String,
    val status: String
)

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustMarkService>())
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
    private val getTrustMarkCommand: GetTrustMarkCommand,
    private val jwkService: JwkService,
    private val jwtService: JwtService,
    private val tenantContextResolver: TenantContextResolver
) : TrustMarkService {

    override suspend fun createTrustMarkType(tenantId: String, createDto: CreateTrustMarkType): FederationResult<TrustMarkType> =
        createTrustMarkTypeCommand.execute(CreateTrustMarkTypeArgs(tenantId, createDto)).toFederationResult()

    override suspend fun findAllByAccount(tenantId: String): FederationResult<List<TrustMarkType>> =
        findAllTrustMarkTypesByAccountCommand.execute(FindAllTrustMarkTypesByAccountArgs(tenantId)).toFederationResult()

    override suspend fun findById(tenantId: String, id: String): FederationResult<TrustMarkType> =
        findTrustMarkTypeByIdCommand.execute(FindTrustMarkTypeByIdArgs(tenantId, id)).toFederationResult()

    override suspend fun deleteTrustMarkType(tenantId: String, id: String): FederationResult<TrustMarkType> =
        deleteTrustMarkTypeCommand.execute(DeleteTrustMarkTypeArgs(tenantId, id)).toFederationResult()

    override suspend fun getIssuersForTrustMarkType(tenantId: String, trustMarkTypeId: String): FederationResult<Array<TrustMarkIssuer>> =
        getIssuersForTrustMarkTypeCommand.execute(GetIssuersForTrustMarkTypeArgs(tenantId, trustMarkTypeId)).toFederationResult()

    override suspend fun addIssuerToTrustMarkType(tenantId: String, trustMarkTypeId: String, issuerIdentifier: String): FederationResult<TrustMarkIssuer> =
        addIssuerToTrustMarkTypeCommand.execute(AddIssuerToTrustMarkTypeArgs(tenantId, trustMarkTypeId, issuerIdentifier)).toFederationResult()

    override suspend fun removeIssuerFromTrustMarkType(tenantId: String, trustMarkTypeId: String, issuerId: String): FederationResult<TrustMarkIssuer> =
        removeIssuerFromTrustMarkTypeCommand.execute(RemoveIssuerFromTrustMarkTypeArgs(tenantId, trustMarkTypeId, issuerId)).toFederationResult()

    override suspend fun getTrustMarksForAccount(tenantId: String): FederationResult<List<TrustMark>> =
        getTrustMarksForAccountCommand.execute(GetTrustMarksForAccountArgs(tenantId)).toFederationResult()

    override suspend fun createTrustMark(tenantId: String, body: CreateTrustMarkRequest, currentTimeMillis: Long): FederationResult<CreateTrustMarkResult> =
        createTrustMarkCommand.execute(CreateTrustMarkArgs(tenantId, body, currentTimeMillis)).toFederationResult()

    override suspend fun deleteTrustMark(tenantId: String, id: String): FederationResult<TrustMarkEntity> =
        deleteTrustMarkCommand.execute(DeleteTrustMarkArgs(tenantId, id)).toFederationResult()

    override suspend fun getTrustMarkStatus(tenantId: String, request: TrustMarkStatusRequest): FederationResult<Boolean> =
        getTrustMarkStatusCommand.execute(GetTrustMarkStatusArgs(tenantId, request)).toFederationResult()
            .andThenSuspend { detail ->
                com.sphereon.core.api.IdkResult.ok(detail.status == TrustMarkStatusValue.ACTIVE)
            }

    override suspend fun getSignedTrustMarkStatusJwt(
        tenantId: String,
        request: TrustMarkStatusRequest,
        trustMarkJwt: String?,
    ): FederationResult<String> {
        return getTrustMarkStatusCommand.execute(
            GetTrustMarkStatusArgs(tenantId, request, trustMarkJwt)
        ).toFederationResult().andThenSuspend { detail ->
            val issuer = tenantContextResolver.resolveIdentifier(tenantId)
                ?: return@andThenSuspend ServerError("Cannot resolve issuer identifier", null, null).toErr()

            val keysResult = jwkService.getKeys(tenantId, includeRevoked = false)
            if (keysResult.isErr) {
                return@andThenSuspend KeyNotFoundError(keyId = "account:$tenantId").toErr()
            }
            val keys = keysResult.value
            if (keys.isEmpty()) {
                return@andThenSuspend KeyNotFoundError(keyId = "account:$tenantId").toErr()
            }

            val key = keys[0]
            val now = System.currentTimeMillis() / 1000
            // Echo the Trust Mark under evaluation (submitted JWT preferred — §8.4.2)
            val markForResponse = trustMarkJwt?.takeIf { it.isNotBlank() }
                ?: detail.trustMarkJwt

            val payload = TrustMarkStatusResponsePayload(
                iss = issuer,
                iat = now.toInt(),
                trust_mark = markForResponse,
                status = detail.status.wire,
            )

            val header = JwtHeader(
                kid = key.kid,
                alg = key.alg ?: "RS256",
                typ = "trust-mark-status-response+jwt"
            )

            jwtService.signPayload(payload, header, key.kid, key.kmsKeyRef, key.kms)
        }
    }

    override suspend fun getTrustMarkedSubs(tenantId: String, request: TrustMarkListRequest): FederationResult<Array<String>> =
        getTrustMarkedSubsCommand.execute(GetTrustMarkedSubsArgs(tenantId, request)).toFederationResult()

    override suspend fun getTrustMark(tenantId: String, request: TrustMarkRequest): FederationResult<String> =
        getTrustMarkCommand.execute(GetTrustMarkArgs(tenantId, request)).toFederationResult()
}

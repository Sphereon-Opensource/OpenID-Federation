package com.sphereon.openid.fed.services

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.toFederationResult

import com.sphereon.openid.fed.openapi.models.TrustAnchorHint
import com.sphereon.openid.fed.services.command.trustAnchorHint.CreateTrustAnchorHintArgs
import com.sphereon.openid.fed.services.command.trustAnchorHint.CreateTrustAnchorHintCommand
import com.sphereon.openid.fed.services.command.trustAnchorHint.DeleteTrustAnchorHintArgs
import com.sphereon.openid.fed.services.command.trustAnchorHint.DeleteTrustAnchorHintCommand
import com.sphereon.openid.fed.services.command.trustAnchorHint.FindTrustAnchorHintsByAccountArgs
import com.sphereon.openid.fed.services.command.trustAnchorHint.FindTrustAnchorHintsByAccountCommand
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustAnchorHintService>())
class TrustAnchorHintServiceImpl(
    private val createTrustAnchorHintCommand: CreateTrustAnchorHintCommand,
    private val deleteTrustAnchorHintCommand: DeleteTrustAnchorHintCommand,
    private val findTrustAnchorHintsByAccountCommand: FindTrustAnchorHintsByAccountCommand
) : TrustAnchorHintService {

    override suspend fun createTrustAnchorHint(tenantId: String, identifier: String): FederationResult<TrustAnchorHint> =
        createTrustAnchorHintCommand.execute(CreateTrustAnchorHintArgs(tenantId, identifier)).toFederationResult()

    override suspend fun deleteTrustAnchorHint(tenantId: String, id: String): FederationResult<TrustAnchorHint> =
        deleteTrustAnchorHintCommand.execute(DeleteTrustAnchorHintArgs(tenantId, id)).toFederationResult()

    override suspend fun findByAccount(tenantId: String): FederationResult<List<TrustAnchorHint>> =
        findTrustAnchorHintsByAccountCommand.execute(FindTrustAnchorHintsByAccountArgs(tenantId)).toFederationResult()
}

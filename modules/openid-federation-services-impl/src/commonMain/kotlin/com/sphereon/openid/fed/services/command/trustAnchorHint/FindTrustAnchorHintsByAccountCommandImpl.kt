package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TrustAnchorHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FindTrustAnchorHintsByAccountCommand::class)
class FindTrustAnchorHintsByAccountCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<FindTrustAnchorHintsByAccountArgs, List<TrustAnchorHint>>(
    commandId = FindTrustAnchorHintsByAccountCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindTrustAnchorHintsByAccountArgs>(),
    outputTypeToken = typeToken<List<TrustAnchorHint>>()
), FindTrustAnchorHintsByAccountCommand {

    private val logger = Log.app().withTag("FindTrustAnchorHintsByAccountCommand")
    private val trustAnchorHintQueries = Persistence.trustAnchorHintQueries

    override suspend fun doExecute(
        args: FindTrustAnchorHintsByAccountArgs,
        applyDuring: (FindTrustAnchorHintsByAccountArgs) -> FindTrustAnchorHintsByAccountArgs
    ): IdkResult<List<TrustAnchorHint>, IdkError> {
        val (tenantId) = applyDuring(args)

        logger.debug("Finding trust anchor hints for account: $tenantId")

        return try {
            val trustAnchorHints = trustAnchorHintQueries.findByAccountId(tenantId)
                .executeAsList()
                .map { it.toDTO() }
            logger.info("Found ${trustAnchorHints.size} trust anchor hints for account: $tenantId")
            IdkResult.ok(trustAnchorHints)
        } catch (e: Exception) {
            logger.error("Failed to find trust anchor hints for account: $tenantId", e)
            federationErr(ServerError("Failed to retrieve trust anchor hints", e.message, e))
        }
    }
}

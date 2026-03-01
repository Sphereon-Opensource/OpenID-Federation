package com.sphereon.openid.fed.services.command.trustAnchorHint

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.InvalidRequestError
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
@ContributesBinding(SessionScope::class, boundType = CreateTrustAnchorHintCommand::class)
class CreateTrustAnchorHintCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateTrustAnchorHintArgs, TrustAnchorHint>(
    commandId = CreateTrustAnchorHintCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateTrustAnchorHintArgs>(),
    outputTypeToken = typeToken<TrustAnchorHint>()
), CreateTrustAnchorHintCommand {

    private val logger = Log.app().withTag("CreateTrustAnchorHintCommand")
    private val trustAnchorHintQueries = Persistence.trustAnchorHintQueries

    override suspend fun doExecute(
        args: CreateTrustAnchorHintArgs,
        applyDuring: (CreateTrustAnchorHintArgs) -> CreateTrustAnchorHintArgs
    ): IdkResult<TrustAnchorHint, IdkError> {
        val (tenantId, identifier) = applyDuring(args)

        logger.debug("Attempting to create trust anchor hint for account: $tenantId with identifier: $identifier")

        val existingTrustAnchorHint = trustAnchorHintQueries
            .findByAccountIdAndIdentifier(tenantId, identifier)
            .executeAsOneOrNull()

        if (existingTrustAnchorHint != null) {
            logger.error("Trust anchor hint already exists for account: $tenantId, identifier: $identifier")
            return federationErr(InvalidRequestError(Constants.TRUST_ANCHOR_HINT_ALREADY_EXISTS))
        }

        return try {
            val created = trustAnchorHintQueries.create(tenantId, identifier).executeAsOneOrNull()?.toDTO()
            if (created != null) {
                logger.info("Successfully created trust anchor hint for account: $tenantId with identifier: $identifier")
                IdkResult.ok(created)
            } else {
                logger.error("Failed to create trust anchor hint for account: $tenantId with identifier: $identifier")
                federationErr(ServerError(Constants.FAILED_TO_CREATE_TRUST_ANCHOR_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to create trust anchor hint for account: $tenantId with identifier: $identifier", e)
            federationErr(ServerError("Failed to create trust anchor hint", e.message, e))
        }
    }
}

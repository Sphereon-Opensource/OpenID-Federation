package com.sphereon.openid.fed.services.command.receivedTrustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.openid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the CreateReceivedTrustMarkCommand.
 * Creates a new received trust mark for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateReceivedTrustMarkCommand::class)
class CreateReceivedTrustMarkCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<CreateReceivedTrustMarkArgs, ReceivedTrustMark, FederationError>(
    id = CreateReceivedTrustMarkCommand.COMMAND_ID,
    execution = execution
), CreateReceivedTrustMarkCommand {

    private val logger = Log.app().withTag("CreateReceivedTrustMarkCommand")
    private val receivedTrustMarkQueries = Persistence.receivedTrustMarkQueries

    override suspend fun createReceivedTrustMark(account: Account, createRequest: CreateReceivedTrustMark): IdkResult<ReceivedTrustMark, FederationError> {
        return execute(CreateReceivedTrustMarkArgs(account, createRequest))
    }

    override suspend fun doExecute(
        args: CreateReceivedTrustMarkArgs,
        applyDuring: (CreateReceivedTrustMarkArgs) -> CreateReceivedTrustMarkArgs
    ): IdkResult<ReceivedTrustMark, FederationError> {
        val (account, createRequest) = applyDuring(args)
        val username = account.username

        logger.info("Creating trust mark for account: $username")

        return try {
            val createdTrustMark = receivedTrustMarkQueries.create(
                account_id = account.id,
                trust_mark_id = createRequest.trustMarkId,
                jwt = createRequest.jwt,
            ).executeAsOneOrNull()

            if (createdTrustMark != null) {
                logger.info("Successfully created trust mark with ID: ${createdTrustMark.id}")
                IdkResult.ok(createdTrustMark.toDTO())
            } else {
                logger.error("Failed to create trust mark for account: $username")
                IdkResult.err(ServerError("Failed to create received trust mark"))
            }
        } catch (e: Exception) {
            logger.error("Failed to create trust mark for account: $username", e)
            IdkResult.err(ServerError("Failed to create received trust mark", e.message, e))
        }
    }
}

package com.sphereon.openid.fed.services.command.authorityHint

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.Constants
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.AuthorityHint
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.mappers.toDTO
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateAuthorityHintCommand.
 * Creates a new authority hint for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateAuthorityHintCommand>())
class CreateAuthorityHintCommandImpl(
    execution: SessionExecution
) : TypedServiceCommandAdapter<CreateAuthorityHintArgs, AuthorityHint, FederationError>(
    commandId = CreateAuthorityHintCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateAuthorityHintArgs>(),
    outputTypeToken = typeToken<AuthorityHint>()
), CreateAuthorityHintCommand {

    private val logger = execution.federationLogger("CreateAuthorityHintCommand")
    private val authorityHintQueries = Persistence.authorityHintQueries

    override suspend fun doExecute(
        args: CreateAuthorityHintArgs,
        applyDuring: (CreateAuthorityHintArgs) -> CreateAuthorityHintArgs
    ): IdkResult<AuthorityHint, FederationError> {
        val (tenantId, identifier) = applyDuring(args)

        logger.debug("Attempting to create authority hint for account: ${tenantId} with identifier: $identifier")

        val existingAuthorityHint = authorityHintQueries
            .findByAccountIdAndIdentifier(tenantId, identifier)
            .executeAsOneOrNull()

        if (existingAuthorityHint != null) {
            logger.error("Authority hint already exists for account: ${tenantId}, identifier: $identifier")
            return federationErr(InvalidRequestError(Constants.AUTHORITY_HINT_ALREADY_EXISTS))
        }

        return try {
            val created = authorityHintQueries.create(tenantId, identifier).executeAsOneOrNull()?.toDTO()
            if (created != null) {
                logger.info("Successfully created authority hint for account: ${tenantId} with identifier: $identifier")
                IdkResult.ok(created)
            } else {
                logger.error("Failed to create authority hint for account: ${tenantId} with identifier: $identifier")
                federationErr(ServerError(Constants.FAILED_TO_CREATE_AUTHORITY_HINT))
            }
        } catch (e: Exception) {
            logger.error("Failed to create authority hint for account: ${tenantId} with identifier: $identifier", e)
            federationErr(ServerError("Failed to create authority hint", e.message, e))
        }
    }
}

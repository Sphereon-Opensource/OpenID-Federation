package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindLatestStoredEntityConfigurationJwtCommand>())
class FindLatestStoredEntityConfigurationJwtCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<FindLatestStoredEntityConfigurationJwtArgs, StoredEntityConfigurationJwt, FederationError>(
    commandId = FindLatestStoredEntityConfigurationJwtCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindLatestStoredEntityConfigurationJwtArgs>(),
    outputTypeToken = typeToken<StoredEntityConfigurationJwt>(),
), FindLatestStoredEntityConfigurationJwtCommand {
    private val logger = execution.federationLogger("FindLatestStoredEntityConfigurationJwtCommand")

    override suspend fun doExecute(
        args: FindLatestStoredEntityConfigurationJwtArgs,
        applyDuring: (FindLatestStoredEntityConfigurationJwtArgs) -> FindLatestStoredEntityConfigurationJwtArgs,
    ): IdkResult<StoredEntityConfigurationJwt, FederationError> {
        val (accountId) = applyDuring(args)
        return try {
            val statement = Persistence.entityConfigurationStatementQueries
                .findLatestByAccountId(accountId)
                .executeAsOneOrNull()
                ?.statement
            IdkResult.ok(StoredEntityConfigurationJwt(statement))
        } catch (e: Exception) {
            logger.error("Failed to load stored entity configuration JWT for account: $accountId", e)
            federationErr(ServerError("Failed to load stored entity configuration JWT", e.message, e))
        }
    }
}

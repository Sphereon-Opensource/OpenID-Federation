package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.CompactEntityStatementJwt
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PersistForeignSubordinateStatementCommand>())
class PersistForeignSubordinateStatementCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<PersistForeignSubordinateStatementArgs, Unit, FederationError>(
    commandId = PersistForeignSubordinateStatementCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<PersistForeignSubordinateStatementArgs>(),
    outputTypeToken = typeToken<Unit>(),
), PersistForeignSubordinateStatementCommand {
    private val logger = execution.federationLogger("PersistForeignSubordinateStatementCommand")

    override suspend fun doExecute(
        args: PersistForeignSubordinateStatementArgs,
        applyDuring: (PersistForeignSubordinateStatementArgs) -> PersistForeignSubordinateStatementArgs,
    ): IdkResult<Unit, FederationError> {
        val (accountId, iss, sub, signedJwt) = applyDuring(args)
        val expiresAt = CompactEntityStatementJwt.expEpochSeconds(signedJwt)
            ?: return federationErr(InvalidRequestError("Foreign subordinate JWT payload is missing exp"))
        return try {
            Persistence.foreignSubordinateStatementQueries.create(
                account_id = accountId,
                iss = iss,
                sub = sub,
                statement = signedJwt,
                expires_at = expiresAt,
            ).executeAsOne()
            IdkResult.ok(Unit)
        } catch (e: Exception) {
            logger.error("Failed to persist foreign subordinate statement for account: $accountId", e)
            federationErr(ServerError("Failed to persist foreign subordinate statement", e.message, e))
        }
    }
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FindForeignSubordinateStatementCommand>())
class FindForeignSubordinateStatementCommandImpl(
    execution: SessionExecution,
) : TypedServiceCommandAdapter<FindForeignSubordinateStatementArgs, ForeignSubordinateJwt, FederationError>(
    commandId = FindForeignSubordinateStatementCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<FindForeignSubordinateStatementArgs>(),
    outputTypeToken = typeToken<ForeignSubordinateJwt>(),
), FindForeignSubordinateStatementCommand {
    override suspend fun doExecute(
        args: FindForeignSubordinateStatementArgs,
        applyDuring: (FindForeignSubordinateStatementArgs) -> FindForeignSubordinateStatementArgs,
    ): IdkResult<ForeignSubordinateJwt, FederationError> {
        val (accountId, iss, sub) = applyDuring(args)
        return try {
            val statement = Persistence.foreignSubordinateStatementQueries
                .findLatestByAccountIssSub(accountId, iss, sub)
                .executeAsOneOrNull()
                ?.statement
            IdkResult.ok(ForeignSubordinateJwt(statement))
        } catch (e: Exception) {
            federationErr(ServerError("Failed to load foreign subordinate statement", e.message, e))
        }
    }
}

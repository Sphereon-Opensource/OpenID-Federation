package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.signPayload
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the PublishEntityConfigurationCommand.
 * Publishes an entity configuration statement for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = PublishEntityConfigurationCommand::class)
class PublishEntityConfigurationCommandImpl(
    execution: SessionExecution,
    private val findEntityConfigurationCommand: FindEntityConfigurationByAccountCommand,
    private val jwkService: JwkService,
    private val jwtService: JwtService
) : ExecutionScopedCommandAdapter<PublishEntityConfigurationArgs, String, FederationError>(
    id = PublishEntityConfigurationCommand.COMMAND_ID,
    execution = execution
), PublishEntityConfigurationCommand {

    private val logger = Log.app().withTag("PublishEntityConfigurationCommand")
    private val queries = Persistence

    override suspend fun publishByAccount(
        account: Account,
        dryRun: Boolean?,
        kmsKeyRef: String?,
        kid: String?
    ): IdkResult<String, FederationError> {
        return execute(PublishEntityConfigurationArgs(account, dryRun, kmsKeyRef, kid), execution.sessionContext)
    }

    override suspend fun doExecute(
        args: PublishEntityConfigurationArgs,
        sessionContext: SessionContext,
        applyDuring: (PublishEntityConfigurationArgs) -> PublishEntityConfigurationArgs
    ): IdkResult<String, FederationError> {
        val (account, dryRun, kmsKeyRef, kid) = applyDuring(args)

        logger.info("Publishing entity configuration for account: ${account.username} (dryRun: $dryRun)")

        // Find the entity configuration
        val findResult = findEntityConfigurationCommand.findByAccount(account)

        if (findResult.isErr) {
            return findResult.error.asErrorResult()
        }
        val entityConfigurationStatement = findResult.value

        // Get the keys for signing
        val keysResult = jwkService.getAssertedKeysForAccount(
            account,
            includeRevoked = false,
            kmsKeyRef = kmsKeyRef,
            kid = kid
        )

        if (keysResult.isErr) {
            return keysResult.error.asErrorResult()
        }
        val keys = keysResult.value
        val key = keys[0]

        // Create signed JWT
        val jwtResult = createSignedJwt(entityConfigurationStatement, key)
        if (jwtResult.isErr) {
            return jwtResult
        }
        val jwt = jwtResult.value

        // Persist or return based on dryRun flag
        return if (dryRun == true) {
            logger.info("Dry run completed, returning JWT without persisting")
            IdkResult.ok(jwt)
        } else {
            val persistResult = persistEntityConfiguration(account, entityConfigurationStatement, jwt)
            if (persistResult.isErr) {
                return persistResult.error.asErrorResult()
            }
            logger.info("Successfully published entity configuration statement for account: ${account.username}")
            IdkResult.ok(jwt)
        }
    }

    private suspend fun createSignedJwt(
        statement: EntityConfigurationStatement,
        key: AccountJwk
    ): IdkResult<String, FederationError> {
        return try {
            val header = JwtHeader(typ = "entity-statement+jwt", kid = key.kid, alg = key.alg ?: "RS256")
            jwtService.signPayload(statement, header, key.kid, key.kmsKeyRef, key.kms)
        } catch (e: Exception) {
            logger.error("Failed to create signed JWT", e)
            IdkResult.err(ServerError("Failed to sign entity configuration", e.message, e))
        }
    }

    private fun persistEntityConfiguration(
        account: Account,
        statement: EntityConfigurationStatement,
        jwt: String
    ): IdkResult<Unit, FederationError> {
        return try {
            queries.entityConfigurationStatementQueries.create(
                account_id = account.id,
                expires_at = statement.exp.toLong(),
                statement = jwt
            ).executeAsOne()
            IdkResult.ok(Unit)
        } catch (e: Exception) {
            logger.error("Failed to persist entity configuration", e)
            IdkResult.err(ServerError("Failed to persist entity configuration", e.message, e))
        }
    }
}

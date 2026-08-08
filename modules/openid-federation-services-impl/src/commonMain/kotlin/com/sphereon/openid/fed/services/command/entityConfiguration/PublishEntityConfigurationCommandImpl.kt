package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.error.toIdkErrorResult
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the PublishEntityConfigurationCommand.
 * Publishes an entity configuration statement for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PublishEntityConfigurationCommand>())
class PublishEntityConfigurationCommandImpl(
    execution: SessionExecution,
    private val findEntityConfigurationCommand: FindEntityConfigurationByAccountCommand,
    private val jwkService: JwkService,
    private val jwtService: JwtService
) : TypedServiceCommandAdapter<PublishEntityConfigurationArgs, String, FederationError>(
    commandId = PublishEntityConfigurationCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<PublishEntityConfigurationArgs>(),
    outputTypeToken = typeToken<String>()
), PublishEntityConfigurationCommand {

    private val logger = execution.federationLogger("PublishEntityConfigurationCommand")
    private val queries = Persistence

    override suspend fun doExecute(
        args: PublishEntityConfigurationArgs,
        applyDuring: (PublishEntityConfigurationArgs) -> PublishEntityConfigurationArgs
    ): IdkResult<String, FederationError> {
        val (tenantId, dryRun, kmsKeyRef, kid) = applyDuring(args)

        logger.info("Publishing entity configuration for account: $tenantId (dryRun: $dryRun)")

        // Find the entity configuration
        val findResult = findEntityConfigurationCommand.execute(FindEntityConfigurationByAccountArgs(tenantId))

        if (findResult.isErr) {
            return findResult.error.asErrorResult()
        }
        val entityConfigurationStatement = findResult.value

        // Get the keys for signing
        val keysResult = jwkService.getAssertedKeysForAccount(
            tenantId,
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
            val persistResult = persistEntityConfiguration(tenantId, entityConfigurationStatement, jwt)
            if (persistResult.isErr) {
                return persistResult.error.asErrorResult()
            }
            logger.info("Successfully published entity configuration statement for account: $tenantId")
            IdkResult.ok(jwt)
        }
    }

    private suspend fun createSignedJwt(
        statement: EntityConfigurationStatement,
        key: TenantJwk
    ): IdkResult<String, FederationError> {
        return try {
            val header = JwtHeader(typ = "entity-statement+jwt", kid = key.kid, alg = key.alg ?: "RS256")
            jwtService.signPayload(statement, header, key.kid, key.kmsKeyRef, key.kms)
        } catch (e: Exception) {
            logger.error("Failed to create signed JWT", e)
            federationErr(ServerError("Failed to sign entity configuration", e.message, e))
        }
    }

    private fun persistEntityConfiguration(
        tenantId: String,
        statement: EntityConfigurationStatement,
        jwt: String
    ): IdkResult<Unit, FederationError> {
        return try {
            queries.entityConfigurationStatementQueries.create(
                account_id = tenantId,
                expires_at = statement.exp.toLong(),
                statement = jwt
            ).executeAsOne()
            IdkResult.ok(Unit)
        } catch (e: Exception) {
            logger.error("Failed to persist entity configuration", e)
            federationErr(ServerError("Failed to persist entity configuration", e.message, e))
        }
    }
}

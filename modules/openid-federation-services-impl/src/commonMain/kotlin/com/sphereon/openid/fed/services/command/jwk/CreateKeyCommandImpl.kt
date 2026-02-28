package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the CreateKeyCommand.
 * Creates a new JSON Web Key (JWK) for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = CreateKeyCommand::class)
class CreateKeyCommandImpl(
    execution: SessionExecution,
    private val keyManagerService: KeyManagerService,
    private val getKeysCommand: GetKeysCommand
) : ExecutionScopedCommandAdapter<CreateKeyCommandArgs, AccountJwk, FederationError>(
    id = CreateKeyCommand.COMMAND_ID,
    execution = execution
), CreateKeyCommand {

    private val logger = Log.app().withTag("CreateKeyCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun createKey(account: Account, opts: CreateKeyArgs): IdkResult<AccountJwk, FederationError> {
        return execute(CreateKeyCommandArgs(account, opts))
    }

    override suspend fun doExecute(
        args: CreateKeyCommandArgs,
        applyDuring: (CreateKeyCommandArgs) -> CreateKeyCommandArgs
    ): IdkResult<AccountJwk, FederationError> = withContext(Dispatchers.IO) {
        val (account, opts) = applyDuring(args)

        try {
            logger.info("Creating new key for account: ${account.username}, with options: $opts")
            logger.debug("Found account with ID: ${account.id}")
            val (providerId, alias, use, keyOperations, alg) = opts

            // Check if key with same alias already exists
            val existingKeysResult = getKeysCommand.getKeys(account, includeRevoked = false)
            if (existingKeysResult.isErr) {
                return@withContext existingKeysResult.error.asErrorResult()
            }
            val existingKeys = existingKeysResult.value

            if (existingKeys.any { alias != null && it.kmsKeyRef == alias }) {
                return@withContext IdkResult.err(ServerError("Key with alias $alias already exists for account ID: ${account.id}"))
            }

            val generatedJwk = keyManagerService.generateKey(providerId, alias, use, keyOperations, alg)
            requireNotNull(generatedJwk.alias) { "Generated key alias cannot be null" }
            requireNotNull(generatedJwk.kid) { "Generated key ID cannot be null" }
            logger.debug("Generated key pair with KID: ${generatedJwk.kid} providerId: ${generatedJwk.providerId} and alias: ${generatedJwk.alias} for account ID: ${account.id}")

            val createdKey = jwkQueries.create(
                account_id = account.id,
                kid = generatedJwk.kid,
                kms_key_ref = generatedJwk.alias,
                kms = generatedJwk.providerId,
                alg = generatedJwk.jose.publicJwk.alg?.value,
                key = generatedJwk.jose.publicJwk.toJsonString()
            ).executeAsOne()

            logger.info("Successfully created key with KID: ${generatedJwk.kid} for account ID: ${account.id}")
            IdkResult.ok(createdKey.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create key for account: ${account.username} due to: ${e.message}", e)
            IdkResult.err(ServerError("Failed to create key", e.message, e))
        }
    }
}

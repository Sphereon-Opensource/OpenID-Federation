package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.AccountJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateKeyCommand.
 * Creates a new JSON Web Key (JWK) for an account.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateKeyCommand>())
class CreateKeyCommandImpl(
    execution: SessionExecution,
    private val keyManagerService: KeyManagerService,
    private val getKeysCommand: GetKeysCommand
) : TypedServiceCommandAdapter<CreateKeyCommandArgs, AccountJwk, FederationError>(
    commandId = CreateKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateKeyCommandArgs>(),
    outputTypeToken = typeToken<AccountJwk>()
), CreateKeyCommand {

    private val logger = execution.federationLogger("CreateKeyCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: CreateKeyCommandArgs,
        applyDuring: (CreateKeyCommandArgs) -> CreateKeyCommandArgs
    ): IdkResult<AccountJwk, FederationError> = withContext(Dispatchers.IO) {
        val (tenantId, opts) = applyDuring(args)

        try {
            logger.info("Creating new key for account: ${tenantId}, with options: $opts")
            logger.debug("Found account with ID: ${tenantId}")
            val (providerId, alias, use, keyOperations, alg) = opts

            // Check if key with same alias already exists
            val existingKeysResult = getKeysCommand.execute(GetKeysArgs(tenantId, includeRevoked = false))
            if (existingKeysResult.isErr) {
                return@withContext existingKeysResult.error.asErrorResult()
            }
            val existingKeys = existingKeysResult.value

            if (existingKeys.any { alias != null && it.kmsKeyRef == alias }) {
                return@withContext federationErr(ServerError("Key with alias $alias already exists for account ID: ${tenantId}"))
            }

            val generatedJwk = keyManagerService.generateKey(providerId, alias, use, keyOperations, alg)
            requireNotNull(generatedJwk.alias) { "Generated key alias cannot be null" }
            requireNotNull(generatedJwk.kid) { "Generated key ID cannot be null" }
            logger.debug("Generated key pair with KID: ${generatedJwk.kid} providerId: ${generatedJwk.providerId} and alias: ${generatedJwk.alias} for account ID: ${tenantId}")

            val createdKey = jwkQueries.create(
                account_id = tenantId,
                kid = generatedJwk.kid,
                kms_key_ref = generatedJwk.alias,
                kms = generatedJwk.providerId,
                alg = generatedJwk.jose.publicJwk.alg?.value,
                key = generatedJwk.jose.publicJwk.toJsonString()
            ).executeAsOne()

            logger.info("Successfully created key with KID: ${generatedJwk.kid} for account ID: ${tenantId}")
            IdkResult.ok(createdKey.toDTO())
        } catch (e: Exception) {
            logger.error("Failed to create key for account: ${tenantId} due to: ${e.message}", e)
            federationErr(ServerError("Failed to create key", e.message, e))
        }
    }
}

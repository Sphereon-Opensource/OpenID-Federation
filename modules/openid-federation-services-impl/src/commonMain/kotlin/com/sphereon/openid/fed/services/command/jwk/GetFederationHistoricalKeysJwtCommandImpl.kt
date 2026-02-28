package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.FederationHistoricalKeysResponse
import com.sphereon.openid.fed.openapi.models.HistoricalKey
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.AccountService
import com.sphereon.openid.fed.services.mappers.toHistoricalKey
import com.sphereon.openid.fed.services.signPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the GetFederationHistoricalKeysJwtCommand.
 * Generates a JWT representing the historical federation keys.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = GetFederationHistoricalKeysJwtCommand::class)
class GetFederationHistoricalKeysJwtCommandImpl(
    execution: SessionExecution,
    private val getKeysCommand: GetKeysCommand,
    private val accountService: AccountService,
    private val jwtService: JwtService
) : ExecutionScopedCommandAdapter<GetFederationHistoricalKeysJwtArgs, String, FederationError>(
    id = GetFederationHistoricalKeysJwtCommand.COMMAND_ID,
    execution = execution
), GetFederationHistoricalKeysJwtCommand {

    companion object {
        private const val JWT_TYPE = "jwk-set+jwt"
    }

    private val logger = Log.app().withTag("GetFederationHistoricalKeysJwtCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun getFederationHistoricalKeysJwt(account: Account): IdkResult<String, FederationError> {
        return execute(GetFederationHistoricalKeysJwtArgs(account))
    }

    override suspend fun doExecute(
        args: GetFederationHistoricalKeysJwtArgs,
        applyDuring: (GetFederationHistoricalKeysJwtArgs) -> GetFederationHistoricalKeysJwtArgs
    ): IdkResult<String, FederationError> = withContext(Dispatchers.IO) {
        val (account) = applyDuring(args)

        try {
            val issResult = accountService.getAccountIdentifierByAccount(account)
            if (issResult.isErr) {
                return@withContext issResult.error.asErrorResult()
            }
            val iss = issResult.value

            val historicalKeys = getFederationHistoricalKeys(account)

            val federationKeysResponse = FederationHistoricalKeysResponse(
                iss = iss,
                iat = (System.currentTimeMillis() / 1000).toInt(),
                propertyKeys = historicalKeys
            )

            val keysResult = getKeysCommand.getKeys(account, includeRevoked = false)
            if (keysResult.isErr) {
                return@withContext keysResult.error.asErrorResult()
            }
            val keys = keysResult.value

            if (keys.isEmpty()) {
                logger.error("No keys found for account: ${account.username}")
                return@withContext IdkResult.err(ServerError("The system is in an invalid state: no keys for account."))
            }

            val key = keys.first()
            val header = JwtHeader(typ = JWT_TYPE, kid = key.kid, alg = key.alg ?: "RS256")
            val jwtResult = jwtService.signPayload(federationKeysResponse, header, key.kid, key.kmsKeyRef, key.kms)

            if (jwtResult.isErr) {
                logger.error("Failed to sign federation historical keys JWT")
                return@withContext jwtResult
            }

            val jwt = jwtResult.value
            logger.trace("Successfully built federation historical keys JWT for username: ${account.username}")
            logger.debug("JWT: $jwt")
            IdkResult.ok(jwt)
        } catch (e: Exception) {
            logger.error("Failed to generate federation historical keys JWT", e)
            IdkResult.err(ServerError("Failed to generate federation historical keys JWT", e.message, e))
        }
    }

    private fun getFederationHistoricalKeys(account: Account): List<HistoricalKey> {
        logger.debug("Retrieving federation historical keys for account: ${account.username}")
        val records = jwkQueries.findByAccountId(account.id).executeAsList()
        logger.debug("Found ${records.size} keys for account ID: ${account.id}")
        return records.map { it.toHistoricalKey() }
    }
}

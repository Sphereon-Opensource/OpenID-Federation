package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.trustChainService.TrustChainServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the VerifyTrustChainCommand.
 * Verifies trust chains according to the OpenID Federation specification.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = VerifyTrustChainCommand::class)
class VerifyTrustChainCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val getHistoricalKeysCommand: GetHistoricalKeysCommand
) : ExecutionScopedCommandAdapter<VerifyTrustChainArgs, VerifyTrustChainResponse, FederationError>(
    id = VerifyTrustChainCommand.COMMAND_ID,
    execution = execution
), VerifyTrustChainCommand {

    private val logger = TrustChainServiceConst.LOG

    override suspend fun verifyTrustChain(
        trustChain: Array<String>,
        trustAnchor: String?,
        currentTime: Long?
    ): IdkResult<VerifyTrustChainResponse, FederationError> {
        return execute(
            VerifyTrustChainArgs(trustChain, trustAnchor, currentTime),
            execution.sessionContext
        )
    }

    override suspend fun doExecute(
        args: VerifyTrustChainArgs,
        sessionContext: SessionContext,
        applyDuring: (VerifyTrustChainArgs) -> VerifyTrustChainArgs
    ): IdkResult<VerifyTrustChainResponse, FederationError> {
        val (chain, trustAnchor, currentTime) = applyDuring(args)

        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()
        if (chain.size < 3) {
            logger.error("Trust chain too short: ${chain.size} statements (minimum 3 required)")
            return IdkResult.ok(VerifyTrustChainResponse(false, "Trust chain must contain at least 3 elements"))
        }

        try {
            logger.debug("Decoding all statements in the chain")
            val statements = chain.map { decodeJWTComponents(it) }
            logger.debug("Current time for validation: $currentTime")

            for (j in statements.indices) {
                val statement = statements[j]
                logger.debug("Verifying statement at position $j")
                logger.debug("Statement $j - Issuer: ${statement.payload["iss"]?.jsonPrimitive?.content}")
                logger.debug("Statement $j - Subject: ${statement.payload["sub"]?.jsonPrimitive?.content}")

                logger.debug("Checking required claims for statement $j")
                if (!hasRequiredClaims(statement)) {
                    logger.error("Statement at position $j missing required claims")
                    return IdkResult.ok(VerifyTrustChainResponse(false, "Statement at position $j missing required claims"))
                }

                val iatTolerance = 5L
                val iat = statement.payload["iat"]?.jsonPrimitive?.content?.toLongOrNull()
                logger.debug("Statement $j - Issued at (iat): $iat")
                logger.debug("Time considered: $timeToUse")
                if (iat == null || iat > timeToUse + iatTolerance) {
                    logger.error("Statement $j has invalid iat: $iat")
                    return IdkResult.ok(VerifyTrustChainResponse(false, "Statement at position $j has invalid iat"))
                }

                val exp = statement.payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
                logger.debug("Statement $j - Expires at (exp): $exp")
                if (exp == null || exp <= timeToUse) {
                    logger.error("Statement $j has expired: $exp")
                    return IdkResult.ok(VerifyTrustChainResponse(false, "Statement at position $j has expired"))
                }

                if (j == 0) {
                    logger.debug("Verifying first statement (ES[0]) specific rules")
                    val iss = statement.payload["iss"]?.jsonPrimitive?.content
                    val sub = statement.payload["sub"]?.jsonPrimitive?.content
                    logger.debug("ES[0] - Comparing iss ($iss) with sub ($sub)")
                    if (iss != sub) {
                        logger.error("First statement iss ($iss) does not match sub ($sub)")
                        return IdkResult.ok(VerifyTrustChainResponse(false, "First statement must have iss == sub"))
                    }

                    logger.debug("Verifying ES[0] signature with its own JWKS")
                    if (!verifySignatureWithOwnJwks(chain[j])) {
                        logger.error("First statement signature verification failed")
                        return IdkResult.ok(VerifyTrustChainResponse(false, "First statement signature verification failed"))
                    }
                }

                if (j < statements.size - 1) {
                    logger.debug("Verifying chain continuity between statements $j and ${j + 1}")
                    val currentIss = statement.payload["iss"]?.jsonPrimitive?.content
                    val nextSub = statements[j + 1].payload["sub"]?.jsonPrimitive?.content
                    logger.debug("Comparing current iss ($currentIss) with next sub ($nextSub)")
                    if (currentIss != nextSub) {
                        logger.error("Chain broken: statement $j iss ($currentIss) does not match statement ${j + 1} sub ($nextSub)")
                        return IdkResult.ok(
                            VerifyTrustChainResponse(
                                false,
                                "Statement chain broken between positions $j and ${j + 1}"
                            )
                        )
                    }

                    logger.debug("Verifying statement $j signature with statement ${j + 1}'s JWKS")
                    if (!verifySignatureWithNextJwks(chain[j], chain[j + 1])) {
                        logger.error("Signature verification failed between statements $j and ${j + 1}")
                        return IdkResult.ok(
                            VerifyTrustChainResponse(
                                false,
                                "Signature verification failed for statement $j with next statement's keys"
                            )
                        )
                    }
                }

                if (j == statements.size - 1) {
                    logger.debug("Verifying trust anchor (last statement)")
                    val lastIss = statement.payload["iss"]?.jsonPrimitive?.content

                    if (trustAnchor != null && lastIss != trustAnchor) {
                        logger.error("Last statement issuer ($lastIss) does not match trust anchor ($trustAnchor)")
                        return IdkResult.ok(VerifyTrustChainResponse(false, "Last statement issuer does not match trust anchor"))
                    }

                    logger.debug("Verifying trust anchor signature with its own JWKS")
                    if (!verifySignatureWithOwnJwks(chain[j])) {
                        logger.error("Trust anchor signature verification failed")
                        return IdkResult.ok(VerifyTrustChainResponse(false, "Trust anchor signature verification failed"))
                    }

                    val trustAnchorEntityConfigResult = getEntityConfigurationCommand.getEntityConfiguration(
                        statement.payload["iss"]?.jsonPrimitive?.content!!
                    )

                    if (trustAnchorEntityConfigResult.isErr) {
                        return IdkResult.ok(VerifyTrustChainResponse(false, "Failed to fetch trust anchor configuration"))
                    }

                    val trustAnchorEntityConfiguration = trustAnchorEntityConfigResult.value
                    val jwks = trustAnchorEntityConfiguration.jwks?.propertyKeys
                    if (jwks != null && jwks.find { it.kid == statement.header.kid } != null) {
                        logger.debug("Trust anchor key found in Entity Configuration Statement JWKS")
                    } else {
                        logger.debug("Key not found in current JWKS, checking historical keys")
                        val historicalKeysResult = getHistoricalKeysCommand.getHistoricalKeys(trustAnchorEntityConfiguration)

                        if (historicalKeysResult.isErr) {
                            logger.error("Failed to fetch historical keys")
                            return IdkResult.ok(VerifyTrustChainResponse(false, "Failed to fetch historical keys"))
                        }

                        val historicalKeys = historicalKeysResult.value
                        if (historicalKeys.find { it.kid == statement.header.kid } == null) {
                            logger.error("Trust anchor kid not found in current JWKS or historical keys")
                            return IdkResult.ok(
                                VerifyTrustChainResponse(
                                    false,
                                    "Trust anchor kid not found in current JWKS or historical keys"
                                )
                            )
                        }
                        logger.debug("Trust anchor key found in historical keys")
                    }
                }
            }

            logger.debug("Trust chain verification completed successfully")
            return IdkResult.ok(VerifyTrustChainResponse(true))
        } catch (e: Exception) {
            logger.error("Chain verification failed with exception", e)
            return IdkResult.ok(VerifyTrustChainResponse(false, "Chain verification failed: ${e.message}"))
        }
    }

    private fun hasRequiredClaims(statement: Jwt): Boolean {
        return statement.payload["sub"] != null &&
                statement.payload["iss"] != null &&
                statement.payload["exp"] != null &&
                statement.payload["iat"] != null &&
                statement.payload["jwks"] != null
    }

    private suspend fun verifySignatureWithOwnJwks(jwt: String): Boolean {
        val decoded = decodeJWTComponents(jwt)

        val jwks: Array<Jwk> =
            context.json.decodeFromString(decoded.payload["jwks"]?.jsonObject?.get("keys").toString())

        context.logger.debug("Decoded JWKS: $jwks")

        val key = jwks.find { it.kid == decoded.header.kid }
            ?: throw IllegalStateException("No matching key found for kid: $decoded.header.kid")
        return context.jwtService.verifyJwtSignature(jwt, key)
    }

    private suspend fun verifySignatureWithNextJwks(jwt: String, nextJwt: String): Boolean {
        val decoded = decodeJWTComponents(jwt)
        val decodedNext = decodeJWTComponents(nextJwt)

        val jwks: Array<Jwk> =
            context.json.decodeFromString(decodedNext.payload["jwks"]?.jsonObject?.get("keys").toString())

        context.logger.debug("Decoded JWKS: $jwks")

        val key = jwks.find { it.kid == decoded.header.kid }
            ?: return false
        return context.jwtService.verifyJwtSignature(jwt, key)
    }
}

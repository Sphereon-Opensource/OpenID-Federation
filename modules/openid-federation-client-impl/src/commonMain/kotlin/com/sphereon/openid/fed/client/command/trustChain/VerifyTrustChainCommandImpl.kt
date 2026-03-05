package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetHistoricalKeysCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.trustChainService.TrustChainServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.NamingConstraints
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
            VerifyTrustChainArgs(trustChain, trustAnchor, currentTime)
        )
    }

    override suspend fun doExecute(
        args: VerifyTrustChainArgs,
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
                val iat = statement.payload["iat"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                logger.debug("Statement $j - Issued at (iat): $iat")
                logger.debug("Time considered: $timeToUse")
                if (iat == null || iat > timeToUse + iatTolerance) {
                    logger.error("Statement $j has invalid iat: $iat")
                    return IdkResult.ok(VerifyTrustChainResponse(false, "Statement at position $j has invalid iat"))
                }

                val exp = statement.payload["exp"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
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

            // Validate constraints from subordinate statements
            // Constraints in statement at position j apply to entities below position j in the chain
            // (i.e., statements at positions 0..j-1)
            val constraintsResult = validateConstraints(statements, chain)
            if (constraintsResult != null) {
                return IdkResult.ok(constraintsResult)
            }

            logger.debug("Trust chain verification completed successfully")
            return IdkResult.ok(VerifyTrustChainResponse(true))
        } catch (e: Exception) {
            logger.error("Chain verification failed with exception", e)
            return IdkResult.ok(VerifyTrustChainResponse(false, "Chain verification failed: ${e.message}"))
        }
    }

    /**
     * Validates constraints from subordinate statements in the trust chain.
     *
     * Per the OpenID Federation 1.1 spec:
     * - `max_path_length`: Maximum number of Intermediates between the entity issuing the constraint
     *   and the leaf entities. A value of 0 means the subordinate must be a leaf.
     * - `naming_constraints.permitted`: Entity Identifiers must match at least one permitted pattern.
     * - `naming_constraints.excluded`: Entity Identifiers must not match any excluded pattern.
     * - `allowed_entity_types`: Subordinate entities must only have the listed entity types.
     *
     * Constraints in a statement at position j (issued by entity at j+1 about entity at j)
     * apply to all entities below position j in the chain.
     *
     * @return a failure response if constraints are violated, or null if all constraints pass
     */
    private fun validateConstraints(statements: List<Jwt>, chain: Array<String>): VerifyTrustChainResponse? {
        val constraintsJson = Json { ignoreUnknownKeys = true }

        // Walk statements from top (trust anchor) down to leaf
        // statements[last] = trust anchor entity config (no constraints to check here)
        // statements[last-1] = subordinate statement about statements[last-2].sub, issued by trust anchor
        // ...
        // statements[1] = subordinate statement about the leaf, issued by first intermediate

        for (j in (statements.size - 1) downTo 1) {
            val statement = statements[j]
            val constraintsElement = statement.payload["constraints"] ?: continue

            val constraints: Constraints = try {
                constraintsJson.decodeFromString(constraintsElement.toString())
            } catch (e: Exception) {
                logger.warn("Failed to parse constraints at position $j: ${e.message}")
                continue
            }

            logger.debug("Validating constraints from statement at position $j")

            // max_path_length: number of intermediates allowed between this entity and the leaves
            // Position j is a subordinate statement. The subject is at position j-1.
            // Intermediates between j-1 and the leaf (position 0) = j - 1 - 1 = j - 2
            // (position 0 is the leaf, positions 1..j-1 are intermediates below j)
            val maxPathLength = constraints.maxPathLength
            if (maxPathLength != null) {
                // Number of intermediates below the constrained entity (position j-1)
                // The leaf is at position 0, so intermediates are positions 1..j-2
                val intermediatesBelow = j - 2
                if (intermediatesBelow > maxPathLength) {
                    logger.error("max_path_length constraint violated at position $j: $intermediatesBelow intermediates > max $maxPathLength")
                    return VerifyTrustChainResponse(
                        false,
                        "Constraint violation: max_path_length ($maxPathLength) exceeded at position $j, found $intermediatesBelow intermediates"
                    )
                }
            }

            // naming_constraints: apply to all entities below this point in the chain
            val namingConstraints = constraints.namingConstraints
            if (namingConstraints != null) {
                for (k in 0 until j) {
                    val entitySub = statements[k].payload["sub"]?.jsonPrimitive?.content ?: continue
                    val namingError = validateNamingConstraints(entitySub, namingConstraints, j, k)
                    if (namingError != null) return namingError
                }
            }

            // allowed_entity_types: check that entities below have only allowed types
            val allowedEntityTypes = constraints.allowedEntityTypes
            if (allowedEntityTypes != null && allowedEntityTypes.isNotEmpty()) {
                for (k in 0 until j) {
                    val entityMetadata = statements[k].payload["metadata"]?.jsonObject ?: continue
                    val entityTypes = entityMetadata.keys
                    for (entityType in entityTypes) {
                        if (entityType !in allowedEntityTypes) {
                            logger.error("allowed_entity_types constraint violated: entity at position $k has type '$entityType' not in allowed list")
                            return VerifyTrustChainResponse(
                                false,
                                "Constraint violation: entity type '$entityType' at position $k not allowed by constraints at position $j"
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Validates an entity identifier against naming constraints.
     * Per spec, `permitted` patterns use suffix matching and `excluded` patterns use suffix matching.
     */
    private fun validateNamingConstraints(
        entityIdentifier: String,
        namingConstraints: NamingConstraints,
        constraintPosition: Int,
        entityPosition: Int
    ): VerifyTrustChainResponse? {
        val permitted = namingConstraints.permitted
        if (permitted != null && permitted.isNotEmpty()) {
            val matches = permitted.any { pattern -> matchesNamingPattern(entityIdentifier, pattern) }
            if (!matches) {
                logger.error("naming_constraints.permitted violated: '$entityIdentifier' at position $entityPosition does not match any permitted pattern")
                return VerifyTrustChainResponse(
                    false,
                    "Constraint violation: entity identifier '$entityIdentifier' at position $entityPosition not permitted by naming constraints at position $constraintPosition"
                )
            }
        }

        val excluded = namingConstraints.excluded
        if (excluded != null && excluded.isNotEmpty()) {
            val matches = excluded.any { pattern -> matchesNamingPattern(entityIdentifier, pattern) }
            if (matches) {
                logger.error("naming_constraints.excluded violated: '$entityIdentifier' at position $entityPosition matches an excluded pattern")
                return VerifyTrustChainResponse(
                    false,
                    "Constraint violation: entity identifier '$entityIdentifier' at position $entityPosition excluded by naming constraints at position $constraintPosition"
                )
            }
        }

        return null
    }

    /**
     * Matches an entity identifier against a naming constraint pattern.
     * Patterns use URL suffix matching: a pattern like "https://example.com" matches
     * any identifier that starts with "https://example.com".
     */
    private fun matchesNamingPattern(identifier: String, pattern: String): Boolean {
        return identifier.startsWith(pattern)
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

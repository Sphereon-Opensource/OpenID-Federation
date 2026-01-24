package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.findKeyInJwks
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.services.trustMarkService.TrustMarkServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.TrustMarkOwner
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the VerifyTrustMarkCommand.
 * Validates Trust Marks according to the OpenID Federation specification.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = VerifyTrustMarkCommand::class)
class VerifyTrustMarkCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand
) : ExecutionScopedCommandAdapter<VerifyTrustMarkArgs, TrustMarkValidationResponse, FederationError>(
    id = VerifyTrustMarkCommand.COMMAND_ID,
    execution = execution
), VerifyTrustMarkCommand {

    private val logger = TrustMarkServiceConst.LOG

    override suspend fun verifyTrustMark(
        trustMark: String,
        trustAnchorConfig: EntityConfigurationStatement,
        currentTime: Long?
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        return execute(
            VerifyTrustMarkArgs(trustMark, trustAnchorConfig, currentTime),
            execution.sessionContext
        )
    }

    override suspend fun doExecute(
        args: VerifyTrustMarkArgs,
        sessionContext: SessionContext,
        applyDuring: (VerifyTrustMarkArgs) -> VerifyTrustMarkArgs
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val (trustMark, trustAnchorConfig, currentTime) = applyDuring(args)

        logger.debug("Starting Trust Mark validation")
        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()

        try {
            // 1. Decode the Trust Mark JWT
            val decodedTrustMark = decodeJWTComponents(trustMark)

            // 2. Check if Trust Mark has expired
            val exp = decodedTrustMark.payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
            if (exp == null || exp <= timeToUse) {
                logger.error("Trust Mark has expired")
                return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark has expired"))
            }

            // 3. Get Trust Mark issuer for signature verification
            val trustMarkIssuer = decodedTrustMark.payload["iss"]?.jsonPrimitive?.content
                ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark missing required issuer claim"))

            // 4. Get Trust Mark identifier
            val trustMarkId = decodedTrustMark.payload["id"]?.jsonPrimitive?.content
                ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark missing required 'id' claim"))

            // 5. Fetch issuer's configuration and verify signature
            logger.debug("Fetching issuer configuration for signature verification")
            val issuerConfigResult = getEntityConfigurationCommand.getEntityConfiguration(trustMarkIssuer)
            if (issuerConfigResult.isErr) {
                return IdkResult.ok(TrustMarkValidationResponse(false, "Failed to fetch issuer configuration: ${issuerConfigResult.error.message.defaultMessage}"))
            }

            val issuerConfig = issuerConfigResult.value
            val signingKey = issuerConfig.jwks.propertyKeys?.find { it.kid == decodedTrustMark.header.kid }
                ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark signing key not found in issuer's JWKS"))

            if (!context.jwtService.verifyJwtSignature(trustMark, signingKey)) {
                logger.error("Trust Mark signature verification failed")
                return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark signature verification failed"))
            }
            logger.debug("Trust Mark signature verified successfully")

            // 6. Check if Trust Mark is recognized in Trust Anchor's configuration
            val trustMarkOwners = trustAnchorConfig.trustMarkOwners?.filterKeys { it == trustMarkId }
            if (trustMarkOwners != null && trustMarkOwners.isNotEmpty()) {
                logger.debug("Validating Trust Mark using trust_mark_owners claim")
                return validateWithTrustMarkOwners(trustMarkId, trustMarkOwners, decodedTrustMark)
            }

            // 7. Check if Trust Mark issuer is in Trust Anchor's trust_mark_issuers
            val trustMarkIssuers = trustAnchorConfig.trustMarkIssuers
            if (trustMarkIssuers != null) {
                logger.debug("Validating Trust Mark using trust_mark_issuers claim")
                return validateWithTrustMarkIssuers(
                    trustMarkId,
                    trustMarkIssuers,
                    decodedTrustMark
                )
            }

            // If neither trust_mark_owners nor trust_mark_issuers is present
            logger.debug("Trust Mark not recognized in federation - no trust_mark_owners or trust_mark_issuers found")
            return IdkResult.ok(
                TrustMarkValidationResponse(
                    false,
                    "Trust Mark not recognized in federation - no trust_mark_owners or trust_mark_issuers found"
                )
            )

        } catch (e: Exception) {
            logger.error("Trust Mark validation failed", e)
            return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark validation failed: ${e.message}"))
        }
    }

    private suspend fun validateWithTrustMarkOwners(
        trustMarkId: String,
        trustMarkOwners: Map<String, TrustMarkOwner>,
        decodedTrustMark: Jwt
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val ownerClaims = trustMarkOwners[trustMarkId]
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark identifier not found in trust_mark_owners"))

        // Verify delegation claim exists
        val delegation = decodedTrustMark.payload["delegation"]?.jsonPrimitive?.content
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark missing required delegation claim"))

        // Verify delegation JWT signature with owner's JWKS
        val ownerJwks = ownerClaims.jwks
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "No JWKS found for Trust Mark owner"))

        val decodedDelegation = decodeJWTComponents(delegation)

        val delegationKey = findKeyInJwks(
            ownerJwks.toTypedArray(),
            decodedDelegation.header.kid
        ) ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Delegation signing key not found in owner's JWKS"))

        if (!context.jwtService.verifyJwtSignature(delegation, delegationKey)) {
            return IdkResult.ok(TrustMarkValidationResponse(false, "Delegation signature verification failed"))
        }

        // Verify delegation issuer matches owner's sub
        val ownerSub = ownerClaims.sub
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark owner missing sub claim"))

        if (decodedDelegation.payload["iss"]?.jsonPrimitive?.content != ownerSub) {
            return IdkResult.ok(TrustMarkValidationResponse(false, "Delegation issuer does not match Trust Mark owner"))
        }

        return IdkResult.ok(TrustMarkValidationResponse(true))
    }

    private fun validateWithTrustMarkIssuers(
        trustMarkId: String,
        trustMarkIssuers: Map<String, List<String>>,
        decodedTrustMark: Jwt
    ): IdkResult<TrustMarkValidationResponse, FederationError> {
        val issuerClaims = trustMarkIssuers[trustMarkId]
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark identifier not found in trust_mark_issuers"))

        // Verify Trust Mark issuer is authorized
        val trustMarkIssuer = decodedTrustMark.payload["iss"]?.jsonPrimitive?.content
            ?: return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark missing required issuer claim"))

        val isAuthorizedIssuer = issuerClaims.any { issuer ->
            issuer == trustMarkIssuer
        }

        if (!isAuthorizedIssuer) {
            return IdkResult.ok(TrustMarkValidationResponse(false, "Trust Mark issuer not authorized"))
        }
        // Signature has already been verified
        return IdkResult.ok(TrustMarkValidationResponse(true))
    }
}
